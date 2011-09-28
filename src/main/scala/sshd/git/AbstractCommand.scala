/*
 * Copyright (c) 2011 Denis Bardadym
 * Distributed under Apache License.
 */

package sshd.git

import java.io.{OutputStream, InputStream}
import actors.Actor
import org.eclipse.jgit.transport.{ReceivePack, UploadPack}
import net.liftweb.common.Loggable
import org.apache.sshd.server.{SessionAware, Environment, ExitCallback, Command}
import org.apache.sshd.server.session.ServerSession
import sshd.DatabasePubKeyAuth
import org.eclipse.jgit.lib.{Repository => JRepository, Constants}
import entity.{Repository, SshKey, User}

abstract sealed class AbstractCommand extends Command with SessionAware with Loggable {

  protected var in: InputStream = null
  protected var out: OutputStream = null
  protected var err: OutputStream = null

  protected var callback: ExitCallback = null

  protected var onExit: Int = EXIT_SUCCESS

  protected var user: User = null
  protected var keys: Seq[SshKey] = null

  val EXIT_SUCCESS = 0
  val EXIT_ERROR = 127


  def setSession(session: ServerSession) {
    keys = session.getAttribute(DatabasePubKeyAuth.SSH_KEYS_KEY)
    user = session.getAttribute(DatabasePubKeyAuth.USER_KEY)
  }

  def setInputStream(in: InputStream) {
    this.in = in
  }

  def destroy() {}

  def setExitCallback(callback: ExitCallback) {
    this.callback = callback
  }

  def start(env: Environment) = {
    new Actor {

      def act() {
        try {
          run(env)
        } finally {
          in.close();
          out.close();
          err.close();
          callback.onExit(onExit);
        }
      }

    }.start();
  }


  def run(env: Environment)

  def setErrorStream(err: OutputStream) {
    this.err = err
  }

  def setOutputStream(out: OutputStream) {
    this.out = out
  }

  protected def error(message: String) {
    err.write(Constants.encode(message + "\n"));
    err.flush();
    onExit = EXIT_ERROR
  }
}

trait WithRepo extends AbstractCommand {
  def withRepo[A](repo: Option[Repository])(f: Repository => A) {
    repo match {
      case Some(r) => f(r)
      case None => error("Repository not founded")
    }
  }

  def doIfHavePermission_?[A](condition: Boolean, repo: JRepository)(f: JRepository => A) =
    if (condition)
      f(repo)
    else
      error("You have no permisson")
}


case class Upload(repoPath: String) extends WithRepo {


  def run(env: Environment) = {
    repoPath.split("/").toList match {
      case repoName :: Nil => {
        withRepo(user.repos.filter(_.name == repoName).headOption) {
          r =>
            doIfHavePermission_?(!keys.filter(key => key.for_user_? || key.for_repo_?(r.name)).isEmpty, r.git) {
              repo => createUploadPack(repo)
            }
        }
      }
      case userName :: repoName :: Nil => {
        User.withLogin(userName) match {
          case Some(u) => {
            withRepo(u.repos.filter(_.name == repoName).headOption) {
              r =>
                doIfHavePermission_?(!r.collaborators.filter(_.login == user.login).isEmpty, r.git) {
                  repo => createUploadPack(repo)
                }
            }
          }
          case None => error("User not founded")
        }
      }
      case _ => error("Invalid repo address")
    }


  }

  def createUploadPack(repo: JRepository) = {
    new UploadPack(repo).upload(in, out, err)
  }

}


case class Receive(repoPath: String) extends WithRepo {
  def run(env: Environment) = {
    repoPath.split("/").toList match {
      case repoName :: Nil => {
        withRepo(user.repos.filter(_.name == repoName).headOption) {
          r =>
            doIfHavePermission_?(!keys.filter(key => key.for_user_? || key.for_repo_?(r.name)).isEmpty, r.git) {
              repo => createReceivePack(repo)
            }
        }
      }
      case userName :: repoName :: Nil => {
        User.withLogin(userName) match {
          case Some(u) => {
            withRepo(u.repos.filter(_.name == repoName).headOption) {
              r =>
                doIfHavePermission_?(!r.collaborators.filter(_.login == user.login).isEmpty, r.git) {
                  repo => createReceivePack(repo)
                }
            }
          }
          case None => error("User not founded")
        }
      }
      case _ => error("Invalid repo address")
    }


  }

  def createReceivePack(repo: JRepository) = {
    val rp = new ReceivePack(repo)

    rp.setAllowCreates(true)
    rp.setAllowDeletes(true)
    rp.setAllowNonFastForwards(true)
    rp.setCheckReceivedObjects(true)

    rp.receive(in, out, err)
  }
}

case class UnRecognizedCommand() extends AbstractCommand {
  def run(env: Environment) = {
    error("This command doesn't supported by this server");
  }
}