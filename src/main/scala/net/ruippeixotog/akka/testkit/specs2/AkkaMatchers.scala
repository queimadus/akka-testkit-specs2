package net.ruippeixotog.akka.testkit.specs2

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.reflect._

import akka.testkit.TestKitBase
import org.specs2.execute.AsResult
import org.specs2.matcher.{ Expectable, MatchResult, Matcher, ValueCheck }
import org.specs2.specification.SpecificationFeatures

trait AkkaMatchers { this: SpecificationFeatures =>

  def receive = new ReceiveMatcherSet[Any](
    _.remainingOrDefault,
    { msg => s"Received message '$msg'" },
    { timeout => s"Timeout ($timeout) while waiting for message" })

  def receiveWithin(max: FiniteDuration) = new ReceiveMatcherSet[Any](
    { _ => max },
    { msg => s"Received message '$msg' within $max" },
    { timeout => s"Didn't receive any message within $timeout" })

  def receiveMessage = receive
  def receiveMessageWithin(max: FiniteDuration) = receiveWithin(max)

  class ReceiveMatcherSet[A: ClassTag](
      timeout: TestKitBase => FiniteDuration,
      receiveOkMsg: AnyRef => String,
      receiveKoMsg: FiniteDuration => String) extends Matcher[TestKitBase] {

    private def getMessage(probe: TestKitBase, timeout: FiniteDuration): Option[AnyRef] =
      Option(probe.receiveOne(timeout))

    def apply[S <: TestKitBase](t: Expectable[S]) = getMessage(t.value, timeout(t.value)) match {
      case None => result(false, "", receiveKoMsg(timeout(t.value)), t)
      case Some(msg) =>
        val r = beAnInstanceOf[A].check(msg)
        result(r.isSuccess, s"${receiveOkMsg(msg)} and ${r.message}", s"${receiveOkMsg(msg)} but ${r.message}", t)
    }

    def apply[B: ClassTag] = new ReceiveMatcherSet[B](timeout, receiveOkMsg, receiveKoMsg)

    def apply(msg: A) = new CheckedMatcher(msg)
    def which[R: AsResult](f: A => R) = new CheckedMatcher(f)
    def like[R: AsResult](f: PartialFunction[A, R]) = new CheckedMatcher(f)

    def allOf[R: AsResult](msg: A, msgs: A*) = new AllOfMatcher(msg +: msgs)

    class CheckedMatcher(check: ValueCheck[A]) extends Matcher[TestKitBase] {

      def apply[S <: TestKitBase](t: Expectable[S]) = getMessage(t.value, timeout(t.value)) match {
        case None => result(false, "", receiveKoMsg(timeout(t.value)), t)
        case Some(msg) =>
          val r = beAnInstanceOf[A].check(msg) and check.check(msg.asInstanceOf[A])
          result(r.isSuccess, s"${receiveOkMsg(msg)} and ${r.message}", s"${receiveOkMsg(msg)} but ${r.message}", t)
      }

      def afterOthers = new AfterOthersMatcher(check)
    }

    class AfterOthersMatcher(check: ValueCheck[A]) extends Matcher[TestKitBase] {

      def apply[S <: TestKitBase](t: Expectable[S]) = {
        def now = System.nanoTime.nanos
        val stop = now + timeout(t.value)

        def recv: MatchResult[S] = {
          getMessage(t.value, stop - now) match {
            case None =>
              val koMessage = s"Timeout (${timeout(t.value)}) while waiting for matching message"
              result(false, "", koMessage, t)

            case Some(msg) =>
              val r = msg must beAnInstanceOf[A] and check.check(msg.asInstanceOf[A])
              if (r.isSuccess) result(true, s"${receiveOkMsg(msg)} and ${r.message}", "", t) else recv
          }
        }
        recv
      }
    }

    class AllOfMatcher(msgs: Seq[A]) extends Matcher[TestKitBase] {

      def apply[S <: TestKitBase](t: Expectable[S]) = {
        def now = System.nanoTime.nanos
        val stop = now + timeout(t.value)

        def recv(missing: Seq[A]): MatchResult[S] = {
          if (missing.isEmpty) result(true, "Received all messages", "", t)
          else {
            getMessage(t.value, stop - now) match {
              case None =>
                val missingMsgs = missing.map { msg => s"'$msg'" }.mkString(", ")
                val koMessage = s"Timeout (${timeout(t.value)}) while waiting for messages $missingMsgs"
                result(false, "", koMessage, t)

              case Some(msg: A) if missing.contains(msg) => recv(missing.diff(msg :: Nil))
              case Some(msg) => result(false, "", s"Received unexpected message '$msg'", t)
            }
          }
        }
        recv(msgs)
      }
    }
  }
}
