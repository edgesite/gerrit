// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.httpd.rpc.project;

import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.common.data.AccessSection;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.common.data.PermissionRule;
import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.Sequences;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.change.ChangeInserter;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.ChangesCollection;
import com.google.gerrit.server.change.PostReviewers;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.git.BatchUpdate;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.gerrit.server.git.UpdateException;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.project.SetParent;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.List;

public class ReviewProjectAccess extends ProjectAccessHandler<Change.Id> {
  interface Factory {
    ReviewProjectAccess create(
        @Assisted("projectName") Project.NameKey projectName,
        @Nullable @Assisted ObjectId base,
        @Assisted List<AccessSection> sectionList,
        @Nullable @Assisted("parentProjectName") Project.NameKey parentProjectName,
        @Nullable @Assisted String message);
  }

  private final ReviewDb db;
  private final Sequences seq;
  private final IdentifiedUser user;
  private final Provider<PostReviewers> reviewersProvider;
  private final ProjectCache projectCache;
  private final ChangesCollection changes;
  private final ChangeInserter.Factory changeInserterFactory;
  private final BatchUpdate.Factory updateFactory;

  @Inject
  ReviewProjectAccess(final ProjectControl.Factory projectControlFactory,
      GroupBackend groupBackend,
      MetaDataUpdate.User metaDataUpdateFactory, ReviewDb db,
      IdentifiedUser user,
      Provider<PostReviewers> reviewersProvider,
      ProjectCache projectCache,
      AllProjectsNameProvider allProjects,
      ChangesCollection changes,
      ChangeInserter.Factory changeInserterFactory,
      BatchUpdate.Factory updateFactory,
      Provider<SetParent> setParent,
      Sequences seq,

      @Assisted("projectName") Project.NameKey projectName,
      @Nullable @Assisted ObjectId base,
      @Assisted List<AccessSection> sectionList,
      @Nullable @Assisted("parentProjectName") Project.NameKey parentProjectName,
      @Nullable @Assisted String message) {
    super(projectControlFactory, groupBackend, metaDataUpdateFactory,
        allProjects, setParent, projectName, base, sectionList,
        parentProjectName, message, false);
    this.db = db;
    this.seq = seq;
    this.user = user;
    this.reviewersProvider = reviewersProvider;
    this.projectCache = projectCache;
    this.changes = changes;
    this.changeInserterFactory = changeInserterFactory;
    this.updateFactory = updateFactory;
  }

  @Override
  protected Change.Id updateProjectConfig(ProjectControl ctl,
      ProjectConfig config, MetaDataUpdate md, boolean parentProjectUpdate)
      throws IOException, OrmException {
    md.setInsertChangeId(true);
    Change.Id changeId = new Change.Id(seq.nextChangeId());
    RevCommit commit =
        config.commitToNewRef(md, new PatchSet.Id(changeId,
            Change.INITIAL_PATCH_SET_ID).toRefName());
    if (commit.getId().equals(base)) {
      return null;
    }

    Change change = new Change(
        getChangeId(commit),
        changeId,
        user.getAccountId(),
        new Branch.NameKey(
            config.getProject().getNameKey(),
            RefNames.REFS_CONFIG),
        TimeUtil.nowTs());
    try (RevWalk rw = new RevWalk(md.getRepository());
        ObjectInserter objInserter = md.getRepository().newObjectInserter();
        BatchUpdate bu = updateFactory.create(
          db, change.getProject(), ctl.getUser(),
          change.getCreatedOn())) {
      bu.setRepository(md.getRepository(), rw, objInserter);
      bu.insertChange(
          changeInserterFactory.create(
                ctl.controlForRef(change.getDest().get()), change, commit)
              .setValidatePolicy(CommitValidators.Policy.NONE)
              .setUpdateRef(false)); // Created by commitToNewRef.
      bu.execute();
    } catch (UpdateException | RestApiException e) {
      throw new IOException(e);
    }

    ChangeResource rsrc;
    try {
      rsrc = changes.parse(changeId);
    } catch (ResourceNotFoundException e) {
      throw new IOException(e);
    }
    addProjectOwnersAsReviewers(rsrc);
    if (parentProjectUpdate) {
      addAdministratorsAsReviewers(rsrc);
    }
    return changeId;
  }

  private static Change.Key getChangeId(RevCommit commit) {
    List<String> idList = commit.getFooterLines(FooterConstants.CHANGE_ID);
    Change.Key changeKey = !idList.isEmpty()
        ? new Change.Key(idList.get(idList.size() - 1).trim())
        : new Change.Key("I" + commit.name());
    return changeKey;
  }

  private void addProjectOwnersAsReviewers(ChangeResource rsrc) {
    final String projectOwners =
        groupBackend.get(SystemGroupBackend.PROJECT_OWNERS).getName();
    try {
      AddReviewerInput input = new AddReviewerInput();
      input.reviewer = projectOwners;
      reviewersProvider.get().apply(rsrc, input);
    } catch (IOException | OrmException | RestApiException e) {
      // one of the owner groups is not visible to the user and this it why it
      // can't be added as reviewer
    }
  }

  private void addAdministratorsAsReviewers(ChangeResource rsrc) {
    List<PermissionRule> adminRules =
        projectCache.getAllProjects().getConfig()
            .getAccessSection(AccessSection.GLOBAL_CAPABILITIES)
            .getPermission(GlobalCapability.ADMINISTRATE_SERVER).getRules();
    for (PermissionRule r : adminRules) {
      try {
        AddReviewerInput input = new AddReviewerInput();
        input.reviewer = r.getGroup().getUUID().get();
        reviewersProvider.get().apply(rsrc, input);
      } catch (IOException | OrmException | RestApiException e) {
        // ignore
      }
    }
  }
}
