package docspell.joex.analysis

import java.nio.file.Path

import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.implicits._

import docspell.common._
import docspell.common.syntax.all._
import docspell.store.Store
import docspell.store.queries.QCollective
import docspell.store.records.REquipment
import docspell.store.records.ROrganization
import docspell.store.records.RPerson

import io.circe.syntax._
import org.log4s.getLogger

/** Maintains a custom regex-ner file per collective for stanford's
  * regexner annotator.
  */
trait RegexNerFile[F[_]] {

  def makeFile(collective: Ident): F[Option[Path]]

}

object RegexNerFile {
  private[this] val logger = getLogger

  case class Config(enabled: Boolean, directory: Path, minTime: Duration)

  def apply[F[_]: Concurrent: ContextShift](
      cfg: Config,
      blocker: Blocker,
      store: Store[F]
  ): Resource[F, RegexNerFile[F]] =
    for {
      dir    <- File.withTempDir[F](cfg.directory, "regexner-")
      writer <- Resource.liftF(Semaphore(1))
    } yield new Impl[F](cfg.copy(directory = dir), blocker, store, writer)

  final private class Impl[F[_]: Concurrent: ContextShift](
      cfg: Config,
      blocker: Blocker,
      store: Store[F],
      writer: Semaphore[F] //TODO allow parallelism per collective
  ) extends RegexNerFile[F] {

    def makeFile(collective: Ident): F[Option[Path]] =
      if (cfg.enabled) doMakeFile(collective)
      else (None: Option[Path]).pure[F]

    def doMakeFile(collective: Ident): F[Option[Path]] =
      for {
        now      <- Timestamp.current[F]
        existing <- NerFile.find[F](collective, cfg.directory, blocker)
        result <- existing match {
          case Some(nf) =>
            val dur = Duration.between(nf.creation, now)
            if (dur > cfg.minTime)
              logger.fdebug(
                s"Cache time elapsed (${dur} > ${cfg.minTime}). Check for new state."
              ) *> updateFile(
                collective,
                now,
                Some(nf)
              )
            else nf.nerFilePath(cfg.directory).some.pure[F]
          case None =>
            updateFile(collective, now, None)
        }
      } yield result

    private def updateFile(
        collective: Ident,
        now: Timestamp,
        current: Option[NerFile]
    ): F[Option[Path]] =
      for {
        lastUpdate <- store.transact(Sql.latestUpdate(collective))
        result <- lastUpdate match {
          case None =>
            (None: Option[Path]).pure[F]
          case Some(lup) =>
            current match {
              case Some(cur) =>
                val nerf =
                  if (cur.updated == lup)
                    logger.fdebug(s"No state change detected.") *> updateTimestamp(
                      cur,
                      now
                    ) *> cur.pure[F]
                  else
                    logger.fdebug(
                      s"There have been state changes for collective '${collective.id}'. Reload NER file."
                    ) *> createFile(lup, collective, now)
                nerf.map(_.nerFilePath(cfg.directory).some)
              case None =>
                createFile(lup, collective, now)
                  .map(_.nerFilePath(cfg.directory).some)
            }
        }
      } yield result

    private def updateTimestamp(nf: NerFile, now: Timestamp): F[Unit] =
      writer.withPermit(for {
        file <- Sync[F].pure(nf.jsonFilePath(cfg.directory))
        _    <- File.mkDir(file.getParent)
        _    <- File.writeString(file, nf.copy(creation = now).asJson.spaces2)
      } yield ())

    private def createFile(
        lastUpdate: Timestamp,
        collective: Ident,
        now: Timestamp
    ): F[NerFile] = {
      def update(nf: NerFile, text: String): F[Unit] =
        writer.withPermit(for {
          jsonFile <- Sync[F].pure(nf.jsonFilePath(cfg.directory))
          _        <- logger.fdebug(s"Writing custom NER file for collective '${collective.id}'")
          _        <- File.mkDir(jsonFile.getParent)
          _        <- File.writeString(nf.nerFilePath(cfg.directory), text)
          _        <- File.writeString(jsonFile, nf.asJson.spaces2)
        } yield ())

      for {
        _     <- logger.finfo(s"Generating custom NER file for collective '${collective.id}'")
        names <- store.transact(QCollective.allNames(collective))
        nerFile = NerFile(collective, lastUpdate, now)
        _ <- update(nerFile, NerFile.mkNerConfig(names))
      } yield nerFile
    }
  }

  object Sql {
    import doobie._
    import docspell.store.qb.DSL._
    import docspell.store.qb._

    def latestUpdate(collective: Ident): ConnectionIO[Option[Timestamp]] = {
      def max_(col: Column[_], cidCol: Column[Ident]): Select =
        Select(max(col).as("t"), from(col.table), cidCol === collective)

      val sql = union(
        max_(ROrganization.T.updated, ROrganization.T.cid),
        max_(RPerson.T.updated, RPerson.T.cid),
        max_(REquipment.T.updated, REquipment.T.cid)
      )
      val t = Column[Timestamp]("t", TableDef(""))

      run(select(max(t)), from(sql, "x"))
        .query[Option[Timestamp]]
        .option
        .map(_.flatten)
    }
  }
}
