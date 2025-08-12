/*
 * Copyright 2015 Normation (http://normation.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.normation.rundeck.plugin.resources

import zio.{IO, UIO, ZIO}

package object rudder {

  extension [A](self: A)
    def fail: IO[A, Nothing] = {
      ZIO.fail(self)
    }
    def succeed: UIO[A] = {
      ZIO.succeed(self)
    }

  /**
   * Just a shorthand for our "that method can fail, so Either it returns an
   * error, or the actual type you were looking for" type.
   */
  type Failable[T] = Either[ErrorMsg, T]

  /**
   * We also define a monadic traversal of a sequence of Failable things for
   * simplicity.
   */
  /*
  object Traverse {
    def apply[T, U](
        seq: Seq[T]
    )(f: T => Either[ErrorMsg, U]): Failable[Seq[U]] = {

      // that's clearly not the canonical way of doing it!
      // (simplest way to avoid stack overflow)

      Right(seq.map { x =>
        f(x) match {
          case Right(y)  => y
          case Left(msg) => return Left(msg)
        }
      })
    }
  }
   */
}
