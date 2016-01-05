/*
 * Copyright 2014 The Guardian
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

package lib

import com.github.nscala_time.time.Imports._
import com.madgag.git._
import com.madgag.github.Implicits._
import com.madgag.github.RepoId
import com.netaporter.uri.Uri
import com.netaporter.uri.dsl._
import com.typesafe.scalalogging.LazyLogging
import lib.Config.Checkpoint
import lib.Implicits._
import lib.RepoSnapshot._
import lib.gitgithub.{IssueUpdater, LabelMapping}
import lib.scalagithub.PullRequest
import lib.travis.TravisApiClient
import monitoring.GitHubQuota.trackQuotaOver
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.joda.time.DateTime
import org.joda.time.format.PeriodFormat
import org.kohsuke.github._
import play.api.Logger

import scala.collection.convert.wrapAsScala._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.util.Success
import scalax.file.ImplicitConversions._

object RepoSnapshot {

  val WorthyOfCommentWindow: Duration = 12.hours

  val travisApiClient = new TravisApiClient(Bot.accessToken)

  def apply(githubRepo: GHRepository)(implicit checkpointSnapshoter: CheckpointSnapshoter): Future[RepoSnapshot] = {
    val conn = Bot.githubCredentials.conn()

    val repoId = RepoId.from(githubRepo.getFullName)
    
    trackQuotaOver(conn, s"RepoSnapshot[${repoId.fullName}]") {
      def isMergedToMaster(pr: PullRequest): Boolean = pr.merged_at.isDefined && pr.base.ref == githubRepo.getDefaultBranch

      val hooksF = Future { githubRepo.getHooks }.map {
        _.flatMap {
          _.getConfig.toMap.get("url").map(_.uri)
        }.toList
      }

      val mergedPullRequestsF: Future[Seq[GHPullRequest]] = (for {
        ghResponse <- Bot.github.listPullRequests(repoId)
      } yield {
        ghResponse.result.filter(isMergedToMaster).take(10).map(pr => githubRepo.getPullRequest(pr.number))
      }) andThen { case cprs => Logger.info(s"Merged Pull Requests fetched: ${cprs.map(_.map(_.getNumber).sorted.reverse)}") }

      val gitRepoF = Future {
        RepoUtil.getGitRepo(
          Bot.parentWorkDir / repoId.owner / repoId.name,
          githubRepo.gitHttpTransportUrl,
          Some(Bot.githubCredentials.git))
      } andThen { case r => Logger.info(s"Git Repo ref count: ${r.map(_.getAllRefs.size)}") }


      for {
        mergedPullRequests <- mergedPullRequestsF
        gitRepo <- gitRepoF
      } yield RepoSnapshot(githubRepo, gitRepo, mergedPullRequests, hooksF, checkpointSnapshoter)
    }
  }
}

case class RepoSnapshot(
  repo: GHRepository,
  gitRepo: Repository,
  mergedPullRequests: Seq[GHPullRequest],
  hooksF: Future[Seq[Uri]]  ,
  checkpointSnapshoter: CheckpointSnapshoter) {
  self =>

  private implicit val (revWalk, reader) = gitRepo.singleThreadedReaderTuple

  lazy val masterCommit:RevCommit = gitRepo.resolve(repo.getDefaultBranch).asRevCommit

  lazy val config = ConfigFinder.config(masterCommit)

  lazy val affectedFoldersByPullRequest: Map[GHPullRequest, Set[String]] = (for {
    pr <- mergedPullRequests
  } yield pr -> GitChanges.affects(pr, config.foldersWithValidConfig)).toMap


  lazy val pullRequestsByAffectedFolder : Map[String, Set[GHPullRequest]] = config.foldersWithValidConfig.map {
    folder => folder -> mergedPullRequests.filter(pr => affectedFoldersByPullRequest(pr).contains(folder)).toSet
  }.toMap

  Logger.info(s"${repo.getFullName} pullRequestsByAffectedFolder : ${pullRequestsByAffectedFolder.mapValues(_.map(_.getNumber))}")

  lazy val activeConfigByPullRequest: Map[GHPullRequest, Set[Checkpoint]] = affectedFoldersByPullRequest.mapValues {
    _.map(config.validConfigByFolder(_).checkpointSet).flatten
  }

  val activeConfig: Set[Checkpoint] = activeConfigByPullRequest.values.reduce(_ ++ _)

  lazy val checkpointSnapshotsF: Map[Checkpoint, Future[CheckpointSnapshot]] = activeConfig.map {
    c =>
      c -> {
        for (possibleIdsTry <- checkpointSnapshoter(c).trying) yield {
          val objectIdTry = for (possibleIds <- possibleIdsTry) yield {
            possibleIds.map(reader.resolveExistingUniqueId).collectFirst {
              case Success(objectId) => objectId
            }
          }
          CheckpointSnapshot(c, objectIdTry)
        }
      }
  }.toMap

  lazy val activeSnapshotsF = Future.sequence(activeConfig.map(checkpointSnapshotsF))

  def checkpointSnapshotsFor(pr: GHPullRequest, oldState: PRCheckpointState): Future[Set[CheckpointSnapshot]] =
    Future.sequence(activeConfigByPullRequest(pr).filter(!oldState.hasSeen(_)).map(checkpointSnapshotsF))

  val issueUpdater = new IssueUpdater[GHPullRequest, PRCheckpointState, PullRequestCheckpointsSummary] with LazyLogging {
    val repo = self.repo

    val pf=PeriodFormat.getDefault()

    val labelToStateMapping = new LabelMapping[PRCheckpointState] {
      def labelsFor(s: PRCheckpointState): Set[String] = s.statusByCheckpoint.map {
        case (checkpointName, cs) => cs.labelFor(checkpointName)
      }.toSet

      def stateFrom(labels: Set[String]): PRCheckpointState = PRCheckpointState(activeConfig.flatMap { checkpoint =>
        PullRequestCheckpointStatus.fromLabels(labels, checkpoint).map(checkpoint.name -> _)
      }.toMap)
    }

    def ignoreItemsWithExistingState(existingState: PRCheckpointState): Boolean =
      existingState.hasStateForCheckpointsWhichHaveAllBeenSeen

    def snapshot(oldState: PRCheckpointState, pr: GHPullRequest) =
      for (cs <- checkpointSnapshotsFor(pr, oldState)) yield PullRequestCheckpointsSummary(pr, cs, gitRepo, oldState)

    override def actionTaker(snapshot: PullRequestCheckpointsSummary) {
      val pr = snapshot.pr

      val newlySeenSnapshots = snapshot.changedSnapshotsByState.get(Seen).toSeq.flatten

      logger.info(s"action taking: ${pr.getId} newlySeenSnapshots = $newlySeenSnapshots")

      for {
        newlySeenSnapshot <- newlySeenSnapshots
        afterSeen <- newlySeenSnapshot.checkpoint.details.afterSeen
        travis <- afterSeen.travis
      } {
        logger.info(s"${pr.getId} going to do $travis")

        travisApiClient.requestBuild(repo.getFullName, travis, repo.getDefaultBranch)
      }

      val mergeToNow = new DateTime(pr.getMergedAt) to DateTime.now
      val previouslyTouchedByProut = snapshot.oldState.statusByCheckpoint.nonEmpty
      if (previouslyTouchedByProut || mergeToNow.duration < WorthyOfCommentWindow) {
        Logger.trace(s"changedSnapshotsByState : ${snapshot.changedSnapshotsByState}")

        val timeSinceMerge = mergeToNow.toPeriod.withMillis(0).toString(pf)
        val mergedByText = s"merged by ${pr.getMergedBy.atLogin} $timeSinceMerge ago"
        val responsibleText = if (pr.getUser == pr.getMergedBy) mergedByText else {
          s"created by ${pr.getUser.atLogin} and $mergedByText"
        }

        def commentOn(status: PullRequestCheckpointStatus, advice: String) = {
          for (changedSnapshots <- snapshot.changedSnapshotsByState.get(status)) {
            val checkpoints = changedSnapshots.map(_.checkpoint.nameMarkdown).mkString(", ")
            pr.comment(s"${status.name} on $checkpoints ($responsibleText) $advice")
          }
        }

        for (hooks <- hooksF) {
          slack.DeployReporter.report(snapshot, hooks)
        }

        commentOn(Seen, "Please check your changes!")
        commentOn(Overdue, "What's gone wrong?")
      }
    }
  }

  def processMergedPullRequests(): Future[Seq[PullRequestCheckpointsSummary]] = for {
    _ <- attemptToCreateMissingLabels()
    _ <- attemptToCreateMissingTestLabels()
    summaryOpts <- Future.traverse(mergedPullRequests)(issueUpdater.process)
  } yield summaryOpts.flatten

  def attemptToCreateMissingLabels(): Future[Set[GHLabel]] = {
    val existingLabels: Set[String] = repo.listLabels.toSet[GHLabel].map(_.getName)

    val labelUpdateFutures: Set[Future[GHLabel]] = for {
      prcs <- PullRequestCheckpointStatus.all
      checkpointName <- config.checkpointsByName.keySet
      label = prcs.labelFor(checkpointName)
      if !existingLabels(label)
    } yield Future { repo.createLabel(label, prcs.defaultColour) }

    Future.sequence(labelUpdateFutures).recover[Set[GHLabel]] { case _ => Set.empty }
  }

  def attemptToCreateMissingTestLabels(): Future[Set[GHLabel]] = {
    val existingLabels: Set[String] = repo.listLabels.toSet[GHLabel].map(_.getName)

    val labelUpdateFutures: Set[Future[GHLabel]] = for {
      prcs <- PullRequestCheckpointTestStatus.all
      checkpointName <- config.checkpointsByName.keySet
      label = prcs.labelFor(checkpointName)
      if !existingLabels(label)
    } yield Future { repo.createLabel(label, prcs.defaultColour) }

    Future.sequence(labelUpdateFutures).recover[Set[GHLabel]] { case _ => Set.empty }
  }
}
