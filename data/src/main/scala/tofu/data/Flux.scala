package tofu.data
import cats.Eval.now
import cats._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.traverse._
import cats.syntax.functor._
import cats.syntax.monoid._
import cats.instances.option._
import cats.instances.either._
import tofu.optics.Applied

object Flux extends FluxInstances {
  trait FluxTag extends Any

  type FluxRepr[+F[+_], +G[+_], +A] <: FluxTag

  def apply[F[+_], G[+_], A](tfa: F[G[(A, Flux[F, G, A])]]): FluxRepr[F, G, A] = tfa.asInstanceOf[FluxRepr[F, G, A]]

  implicit class FluxValueExtract[F[+_], G[+_], A](private val repr: FluxRepr[F, G, A]) extends AnyVal {
    def value: F[G[(A, Flux[F, G, A])]] = repr.asInstanceOf[F[G[(A, Flux[F, G, A])]]]
  }

  type Infinite[+F[+_], A]   = Flux[F, Identity, A]
  type Stream[+F[+_], +A]    = Flux[F, Option, A]
  type Accum[+F[+_], +R, +A] = Flux[F, Either[R, +*], A]

  def stream[F[+_]] = new StreamApply[F]
  class StreamApply[F[+_]] {
    def apply[T[_]: Foldable, A](xs: T[A])(implicit F: Applicative[F], DF: Defer[F]): Stream[F, A] =
      xs.foldRight[Stream[F, A]](now(Flux(F.pure(None))))((a, eb) => now(Flux(DF.defer(F.pure(Some((a, eb.value)))))))
        .value
  }

  final implicit def toFluxOps[F[+_], G[+_], A](flux: Flux[F, G, A]): FluxOps[F, G, A] = new FluxOps(flux.value)
  final implicit def toStreamOps[F[+_], A](flux: Flux[F, Option, A]): FluxStreamOps[F, A] =
    new FluxStreamOps(flux.value)

  object Stream {
    def range[F[+_]: Applicative, N](from: N, to: N, by: N)(implicit N: Numeric[N]): Stream[F, N] = ???

    def range[F[+_]: Applicative, N: Numeric](from: N, to: N): Stream[F, N] =
      range[F, N](from, to, implicitly[Numeric[N]].one)

    def apply[F[+_]] = new Applied[F]

    class Applied[F[+_]] {
      def apply[T[_]: Foldable, A](ta: T[A])(implicit F: Applicative[F]): Stream[F, A] = ???
    }
  }
}

class FluxStreamOps[F[+_], A](private val value: F[Option[(A, Flux.Stream[F, A])]]) extends AnyVal {
  def foldMap[M: Monoid](f: A => M)(implicit F: Monad[F]): F[M] =
    value.flatMap {
      case None               => Monoid.empty[M].pure[F]
      case Some((head, tail)) => Flux.toStreamOps(tail).foldMap(f).map(f(head) |+| _)
    }

  def foldMapK[M[_], B](f: A => M[B])(implicit F: Monad[F], M: MonoidK[M]): F[M[B]] =
    foldMap[M[B]](f)(M.algebra[B], F)
}

class FluxOps[F[+_], G[+_], A](private val value: F[G[(A, Flux[F, G, A])]]) extends AnyVal {
  def mapK[H[+_]: Functor](f: F ~> H)(implicit G: Functor[G]): Flux[H, G, A] =
    Flux(f(value).map(_.map { case (a, flx) => (a, flx.mapK(f)) }))

  def flatMapF[B](f: A => F[B])(implicit F: Monad[F], G: Traverse[G]): Flux[F, G, B] =
    Flux[F, G, B](value.flatMap(_.traverse[F, (B, Flux[F, G, B])] {
      case (a, flux) => f(a).tupleRight(flux.flatMapF(f))
    }))
}

trait FluxInstances extends FluxInstances1 { self: Flux.type =>
  implicit def streamMonad[F[+_]: Monad]: Monad[Stream[F, *]] with Alternative[Stream[F, *]] =
    new FluxFunctor[F, Option] with StackSafeMonad[Stream[F, *]] with Alternative[Stream[F, *]] {
      def pure[A](x: A) = Flux(Some((x, empty[A])).pure[F])

      def flatMap[A, B](fa: Stream[F, A])(f: A => Stream[F, B]): Stream[F, B] =
        Flux(fa.value.flatMap {
          case None               => None.pure[F]
          case Some((head, tail)) => combineK(f(head), flatMap(tail)(f)).value
        })

      def empty[A]: Stream[F, A] = Flux(None.pure[F])

      def combineK[A](x: Stream[F, A], y: Stream[F, A]): Stream[F, A] =
        Flux(x.value.flatMap {
          case None               => y.value
          case Some((head, tail)) => Some((head, combineK(tail, y))).pure[F]
        })
    }

  implicit def accumMonad[F[+_]: Monad, R: Monoid]: Monad[Accum[F, R, *]] with Alternative[Accum[F, R, *]] =
    new FluxFunctor[F, Either[R, +*]] with StackSafeMonad[Accum[F, R, *]] with Alternative[Accum[F, R, *]] {
      private def add[A](r: R, f: Accum[F, R, A]): Accum[F, R, A] =
        Flux(f.value.map {
          case Left(r1)            => Left(r |+| r1)
          case Right((head, tail)) => Right((head, add(r, tail)))
        })

      def empty[A]: Accum[F, R, A]      = Flux(Left(Monoid.empty[R]).pure[F])
      def pure[A](x: A): Accum[F, R, A] = Flux(Right((x, empty[A])).pure[F])
      def combineK[A](x: Accum[F, R, A], y: Accum[F, R, A]): Accum[F, R, A] =
        Flux(x.value.flatMap {
          case Left(r)             => add(r, y).value
          case Right((head, tail)) => Right((head, combineK(tail, y))).pure[F]
        })
      def flatMap[A, B](fa: Accum[F, R, A])(f: A => Accum[F, R, B]): Accum[F, R, B] =
        Flux(fa.value.flatMap {
          case Left(r)             => Left(r).pure[F]
          case Right((head, tail)) => combineK(f(head), flatMap(tail)(f)).value
        })
    }

  implicit def infiniteApplicative[F[+_]: Applicative: Defer]: Applicative[Infinite[F, *]] =
    new Applicative[Infinite[F, *]] {
      def pure[A](x: A): Infinite[F, A] = {
        lazy val result: Infinite[F, A] = Flux[F, Identity, A](Defer[F].defer((x, result).pure[F]))
        result
      }

      override def map2[A, B, Z](fa: Infinite[F, A], fb: Infinite[F, B])(f: (A, B) => Z): Infinite[F, Z] =
        Flux[F, Identity, Z](fa.value.map2(fb.value)((ap, bp) => (f(ap._1, bp._1), map2(ap._2, bp._2)(f))))

      def ap[A, B](ff: Infinite[F, A => B])(fa: Infinite[F, A]): Infinite[F, B] = map2(ff, fa)(_(_))
    }
}
trait FluxInstances1 { self: Flux.type =>
  implicit def fluxFunctor[F[+_]: Functor, G[+_]: Functor]: Functor[Flux[F, G, *]] = new FluxFunctor[F, G]
}

class FluxFunctor[F[+_]: Functor, G[+_]: Functor] extends Functor[Flux[F, G, *]] {
  override def map[A, B](fa: Flux[F, G, A])(f: A => B): Flux[F, G, B] =
    Flux(fa.value.map(_.map { case (head, tail) => (f(head), map(tail)(f)) }))
}
