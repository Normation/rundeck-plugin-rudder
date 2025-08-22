package com.normation.rundeck.plugin.resources.rudder

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import zio.UIO
import zio.ZIO

opaque type RudderLogger = Logger

object RudderLogger {

  // create from an existing SLF4J logger
  def apply(logger: Logger): RudderLogger = logger

  // create from a name (relying on logback cache)
  def apply(loggerName: String): RudderLogger =
    RudderLogger(LoggerFactory.getLogger(loggerName))
}

def effectUioUnit[A](effect: => A): UIO[Unit] = {
  def printError(t: Throwable): UIO[Unit] = {
    val print = (s: String) => ZIO.attempt(java.lang.System.err.println(s))
    // here, we must unit.orDie, because if it fails we can't do much more (and the app is certainly totally broken)
    (print(s"${t.getClass.getName}:${t.getMessage}") *> ZIO.foreach(
      t.getStackTrace.toList
    )(st => print(st.toString))).unit.orDie
  }

  effectUioUnit(printError(_))(effect)
}

def effectUioUnit[A](error: Throwable => UIO[Unit])(effect: => A): UIO[Unit] =
  ZIO.attempt(effect).unit.catchAll(error)

extension (logger: RudderLogger) {

  def logEffect: Logger = logger
  def logAndForgetResult[T](log: Logger => T): UIO[Unit] =
    effectUioUnit(log(logger))

  def trace(msg: => String): UIO[Unit] =
    ZIO.when(logger.isTraceEnabled())(logAndForgetResult(_.trace(msg))).unit
  def debug(msg: => String): UIO[Unit] =
    ZIO.when(logger.isDebugEnabled())(logAndForgetResult(_.debug(msg))).unit
  def info(msg: => String): UIO[Unit] =
    ZIO.when(logger.isInfoEnabled())(logAndForgetResult(_.info(msg))).unit
  def warn(msg: => String): UIO[Unit] =
    ZIO.when(logger.isWarnEnabled())(logAndForgetResult(_.warn(msg))).unit
  def error(msg: => String): UIO[Unit] =
    ZIO.when(logger.isErrorEnabled())(logAndForgetResult(_.error(msg))).unit

  def trace(msg: => String, t: Throwable): UIO[Unit] =
    ZIO
      .when(logger.isTraceEnabled())(logAndForgetResult(_.trace(msg, t)))
      .unit
  def debug(msg: => String, t: Throwable): UIO[Unit] =
    ZIO
      .when(logger.isDebugEnabled())(logAndForgetResult(_.debug(msg, t)))
      .unit
  def info(msg: => String, t: Throwable): UIO[Unit] =
    ZIO.when(logger.isInfoEnabled())(logAndForgetResult(_.info(msg, t))).unit
  def warn(msg: => String, t: Throwable): UIO[Unit] =
    ZIO.when(logger.isErrorEnabled())(logAndForgetResult(_.warn(msg, t))).unit
  def error(msg: => String, t: Throwable): UIO[Unit] =
    ZIO.when(logger.isWarnEnabled())(logAndForgetResult(_.error(msg, t))).unit

}
