// Copyright (c) 2013-2018 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.free

import cats.~>
import cats.effect.Async
import cats.free.{ Free => FF } // alias because some algebras have an op called Free

import java.lang.String
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.util.Properties
import java.util.logging.Logger

@SuppressWarnings(Array("org.wartremover.warts.Overloading"))
object driver { module =>

  // Algebra of operations for Driver. Each accepts a visitor as an alternatie to pattern-matching.
  sealed trait DriverOp[A] {
    def visit[F[_]](v: DriverOp.Visitor[F]): F[A]
  }

  // Free monad over DriverOp.
  type DriverIO[A] = FF[DriverOp, A]

  // Module of instances and constructors of DriverOp.
  object DriverOp {

    // Given a Driver we can embed a DriverIO program in any algebra that understands embedding.
    implicit val DriverOpEmbeddable: Embeddable[DriverOp, Driver] =
      new Embeddable[DriverOp, Driver] {
        def embed[A](j: Driver, fa: FF[DriverOp, A]) = Embedded.Driver(j, fa)
      }

    // Interface for a natural tansformation DriverOp ~> F encoded via the visitor pattern.
    // This approach is much more efficient than pattern-matching for large algebras.
    trait Visitor[F[_]] extends (DriverOp ~> F) {
      final def apply[A](fa: DriverOp[A]): F[A] = fa.visit(this)

      // Common
      def raw[A](f: Driver => A): F[A]
      def embed[A](e: Embedded[A]): F[A]
      def delay[A](a: () => A): F[A]
      def handleErrorWith[A](fa: DriverIO[A], f: Throwable => DriverIO[A]): F[A]
      def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]

      // Driver
      def acceptsURL(a: String): F[Boolean]
      def connect(a: String, b: Properties): F[Connection]
      def getMajorVersion: F[Int]
      def getMinorVersion: F[Int]
      def getParentLogger: F[Logger]
      def getPropertyInfo(a: String, b: Properties): F[Array[DriverPropertyInfo]]
      def jdbcCompliant: F[Boolean]

    }

    // Common operations for all algebras.
    final case class Raw[A](f: Driver => A) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.raw(f)
    }
    final case class Embed[A](e: Embedded[A]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.embed(e)
    }
    final case class Delay[A](a: () => A) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.delay(a)
    }
    final case class HandleErrorWith[A](fa: DriverIO[A], f: Throwable => DriverIO[A]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.handleErrorWith(fa, f)
    }
    final case class Async1[A](k: (Either[Throwable, A] => Unit) => Unit) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.async(k)
    }

    // Driver-specific operations.
    final case class  AcceptsURL(a: String) extends DriverOp[Boolean] {
      def visit[F[_]](v: Visitor[F]) = v.acceptsURL(a)
    }
    final case class  Connect(a: String, b: Properties) extends DriverOp[Connection] {
      def visit[F[_]](v: Visitor[F]) = v.connect(a, b)
    }
    final case object GetMajorVersion extends DriverOp[Int] {
      def visit[F[_]](v: Visitor[F]) = v.getMajorVersion
    }
    final case object GetMinorVersion extends DriverOp[Int] {
      def visit[F[_]](v: Visitor[F]) = v.getMinorVersion
    }
    final case object GetParentLogger extends DriverOp[Logger] {
      def visit[F[_]](v: Visitor[F]) = v.getParentLogger
    }
    final case class  GetPropertyInfo(a: String, b: Properties) extends DriverOp[Array[DriverPropertyInfo]] {
      def visit[F[_]](v: Visitor[F]) = v.getPropertyInfo(a, b)
    }
    final case object JdbcCompliant extends DriverOp[Boolean] {
      def visit[F[_]](v: Visitor[F]) = v.jdbcCompliant
    }

  }
  import DriverOp._

  // Smart constructors for operations common to all algebras.
  val unit: DriverIO[Unit] = FF.pure[DriverOp, Unit](())
  def pure[A](a: A): DriverIO[A] = FF.pure[DriverOp, A](a)
  def raw[A](f: Driver => A): DriverIO[A] = FF.liftF(Raw(f))
  def embed[F[_], J, A](j: J, fa: FF[F, A])(implicit ev: Embeddable[F, J]): FF[DriverOp, A] = FF.liftF(Embed(ev.embed(j, fa)))
  def delay[A](a: => A): DriverIO[A] = FF.liftF(Delay(() => a))
  def handleErrorWith[A](fa: DriverIO[A], f: Throwable => DriverIO[A]): DriverIO[A] = FF.liftF[DriverOp, A](HandleErrorWith(fa, f))
  def raiseError[A](err: Throwable): DriverIO[A] = delay(throw err)
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): DriverIO[A] = FF.liftF[DriverOp, A](Async1(k))

  // Smart constructors for Driver-specific operations.
  def acceptsURL(a: String): DriverIO[Boolean] = FF.liftF(AcceptsURL(a))
  def connect(a: String, b: Properties): DriverIO[Connection] = FF.liftF(Connect(a, b))
  val getMajorVersion: DriverIO[Int] = FF.liftF(GetMajorVersion)
  val getMinorVersion: DriverIO[Int] = FF.liftF(GetMinorVersion)
  val getParentLogger: DriverIO[Logger] = FF.liftF(GetParentLogger)
  def getPropertyInfo(a: String, b: Properties): DriverIO[Array[DriverPropertyInfo]] = FF.liftF(GetPropertyInfo(a, b))
  val jdbcCompliant: DriverIO[Boolean] = FF.liftF(JdbcCompliant)

  // DriverIO is an Async
  implicit val AsyncDriverIO: Async[DriverIO] =
    new Async[DriverIO] {
      val M = FF.catsFreeMonadForFree[DriverOp]
      def pure[A](x: A): DriverIO[A] = M.pure(x)
      def handleErrorWith[A](fa: DriverIO[A])(f: Throwable => DriverIO[A]): DriverIO[A] = module.handleErrorWith(fa, f)
      def raiseError[A](e: Throwable): DriverIO[A] = module.raiseError(e)
      def async[A](k: (Either[Throwable,A] => Unit) => Unit): DriverIO[A] = module.async(k)
      def flatMap[A, B](fa: DriverIO[A])(f: A => DriverIO[B]): DriverIO[B] = M.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => DriverIO[Either[A, B]]): DriverIO[B] = M.tailRecM(a)(f)
      def suspend[A](thunk: => DriverIO[A]): DriverIO[A] = M.flatten(module.delay(thunk))
    }

}

