package com.agilogy.srdb.test

import java.sql._

import com.agilogy.srdb._
import com.agilogy.srdb.exceptions._
import org.scalamock.scalatest.MockFactory
import org.scalatest.FlatSpec

import scala.reflect.ClassTag

class ExceptionsTest extends FlatSpec with MockFactory {

  import Srdb._

  val conn: Connection = mock[Connection]
  val ps: PreparedStatement = mock[PreparedStatement]
  val rs: ResultSet = mock[ResultSet]

  val sql = "select * from foo"
  val testReader: (ResultSet) => Seq[String] = readSeq(_.getString("name"))

  ignore should "throw exceptions untranslated" in {
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).throwing(new SQLException("ouch!", "12345", 123))
    }
    val res = intercept[SQLException](select(sql)(testReader)(conn))
    assert(res.getMessage === "Error executing SQL select * from foo")
  }

  it should "translate thrown exceptions" in {
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).throwing(new SQLException("ouch!", "12345", 123))
    }
    val et: ExceptionTranslator = {
      case (_, _, _) => new RuntimeException("Translated!")
    }
    val db = withExceptionTranslator(et)
    val res = intercept[RuntimeException](db.select(sql)(testReader)(conn))
    assert(res.getMessage === "Translated!")
  }

  behavior of "DefaultExceptionTransator for select exceptions"

  it should "notify exceptions preparing the statement" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects("wrong sql", Statement.NO_GENERATED_KEYS).throwing(exc)
    }
    val res = intercept[DbExceptionWithCause[SQLException]] {
      select("wrong sql")(testReader)(conn)
    }
    assert(res.context === Context.PrepareStatement(autogeneratedKeys = false))
    assert(res.sql === "wrong sql")
    assert(res.causedBy === Some(exc))
    assert(res.getMessage ===
      """Error when preparing the statement
         |  SQL: "wrong sql"
         |  Cause: An exception of type java.sql.SQLException was thrown
         |  Cause message: ouch!""".stripMargin)
  }

  private def cast[T <: Throwable: ClassTag](throwable: Throwable): T = {
    val classTag = implicitly[ClassTag[T]]
    if (throwable.getClass == classTag.runtimeClass) throwable.asInstanceOf[T]
    else throw throwable

  }

  it should "notify exceptions setting the arguments" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).returning(ps)
      (ps.setInt _).expects(1, 3).throwing(exc)
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        val arg1: Argument = _.setInt(_, 3)
        select(sql)(testReader)(conn, arg1)
      }
    }
    assert(res.context === Context.SetArguments)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions setting the fetch size" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).returning(ps)
      (ps.setInt _).expects(1, 3)
      (ps.setFetchSize _).expects(200).throwing(exc)
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        val arg1: Argument = _.setInt(_, 3)
        select(sql, LimitedFetchSize(200))(testReader)(conn, arg1)
      }
    }
    assert(res.context === Context.SetFetchSize(200))
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions executing queries" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).returning(ps)
      (ps.executeQuery _).expects().throwing(exc)
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        select(sql)(testReader)(conn)
      }
    }
    assert(res.context === Context.ExecuteQuery)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions reading the ResultSet" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).returning(ps)
      (ps.executeQuery _).expects().returning(rs)
      (rs.next _).expects().returning(true)
      (rs.getString(_: String)).expects("name").throwing(exc)
      (rs.close _).expects()
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        select(sql)(testReader)(conn)
      }
    }
    assert(res.context === Context.ReadResultSet)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions advancing ResultSet" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).returning(ps)
      (ps.executeQuery _).expects().returning(rs)
      (rs.next _).expects().returning(true)
      (rs.next _).expects().throwing(exc)
      (rs.close _).expects()
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        select(sql) {
          rs =>
            rs.next()
            rs.next()
        }(conn)
      }
    }
    assert(res.context === Context.ReadResultSet)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  behavior of "update exceptions"

  it should "notify exceptions executing the update" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.NO_GENERATED_KEYS).returning(ps)
      (ps.executeUpdate _).expects().throwing(exc)
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        update(sql)(conn)
      }
    }
    assert(res.context === Context.ExecuteUpdate)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions getting the generated key" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.RETURN_GENERATED_KEYS).returning(ps)
      (ps.executeUpdate _).expects()
      (ps.getGeneratedKeys _).expects().throwing(exc)
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        updateGeneratedKeys(sql)(testReader)(conn)
      }
    }
    assert(res.context === Context.GetGeneratedKeys)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions reading the generated key" in {
    val exc = new SQLException("ouch!", "12345", 123)
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.RETURN_GENERATED_KEYS).returning(ps)
      (ps.executeUpdate _).expects()
      (ps.getGeneratedKeys _).expects().returning(rs)
      (rs.next _).expects().returning(true)
      (rs.getString(_: String)).expects("name").throwing(exc)
      (rs.close _).expects()
      (ps.close _).expects()
    }
    val res = cast[DbExceptionWithCause[SQLException]] {
      intercept[Exception] {
        updateGeneratedKeys(sql)(_.getString("name"))(conn)
      }
    }
    assert(res.context === Context.ReadGeneratedKeys)
    assert(res.sql === sql)
    assert(res.causedBy === Some(exc))
  }

  it should "notify exceptions when no generated key is generated but one is asked for" in {
    inSequence {
      (conn.prepareStatement(_: String, _: Int)).expects(sql, Statement.RETURN_GENERATED_KEYS).returning(ps)
      (ps.executeUpdate _).expects()
      (ps.getGeneratedKeys _).expects().returning(rs)
      (rs.next _).expects().returning(false)
      (rs.close _).expects()
      (ps.close _).expects()
    }
    val res = cast[NoKeysGenerated] {
      intercept[Exception] {
        updateGeneratedKeys(sql)(_.getString("name"))(conn)
      }
    }
    assert(res.context === Context.GetGeneratedKeys)
    assert(res.sql === sql)
    assert(res.causedBy === None)
    assert(res.getMessage ===
      s"""Error when getting the ResultSet of the generated key
        |  SQL: "$sql"
        |  Cause: No key was generated by the execution of the statement""".stripMargin)
  }
}
