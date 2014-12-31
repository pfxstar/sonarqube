/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.issue.ws;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.security.DefaultGroups;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.KeyValueFormat;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.SnapshotDto;
import org.sonar.core.issue.db.*;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.rule.RuleDto;
import org.sonar.core.user.UserDto;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.component.SnapshotTesting;
import org.sonar.server.db.DbClient;
import org.sonar.server.issue.IssueQuery;
import org.sonar.server.issue.IssueTesting;
import org.sonar.server.issue.db.IssueDao;
import org.sonar.server.issue.filter.IssueFilterParameters;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.permission.InternalPermissionService;
import org.sonar.server.permission.PermissionChange;
import org.sonar.server.rule.RuleTesting;
import org.sonar.server.rule.db.RuleDao;
import org.sonar.server.search.QueryContext;
import org.sonar.server.tester.ServerTester;
import org.sonar.server.user.MockUserSession;
import org.sonar.server.ws.WsTester;

import static org.fest.assertions.Assertions.assertThat;

public class SearchActionMediumTest {

  @ClassRule
  public static ServerTester tester = new ServerTester();

  DbClient db;
  DbSession session;
  WsTester wsTester;
  RuleDto rule;
  ComponentDto project;
  ComponentDto file;
  ComponentDto otherFile;

  @Before
  public void setUp() throws Exception {
    tester.clearDbAndIndexes();
    db = tester.get(DbClient.class);
    wsTester = tester.get(WsTester.class);
    session = db.openSession(false);

    rule = RuleTesting.newXooX1()
      .setName("Rule name")
      .setDescription("Rule desc")
      .setStatus(RuleStatus.READY);
    tester.get(RuleDao.class).insert(session, rule);

    project = ComponentTesting.newProjectDto().setUuid("ABCD").setProjectUuid("ABCD")
      .setKey("MyProject");
    db.componentDao().insert(session, project);
    db.snapshotDao().insert(session, SnapshotTesting.createForProject(project));
    session.commit();

    // project can be seen by anyone
    MockUserSession.set().setLogin("admin").setGlobalPermissions(GlobalPermissions.SYSTEM_ADMIN);
    tester.get(InternalPermissionService.class).addPermission(new PermissionChange().setComponentKey(project.getKey()).setGroup(DefaultGroups.ANYONE).setPermission(UserRole.USER));
    tester.get(InternalPermissionService.class).addPermission(
      new PermissionChange().setComponentKey(project.getKey()).setGroup(DefaultGroups.ANYONE).setPermission(UserRole.CODEVIEWER));

    file = ComponentTesting.newFileDto(project).setUuid("BCDE")
      .setKey("MyComponent")
      .setParentProjectId(project.getId());
    db.componentDao().insert(session, file);

    otherFile = ComponentTesting.newFileDto(project).setUuid("FEDC")
      .setKey("OtherComponent")
      .setParentProjectId(project.getId());
    db.componentDao().insert(session, otherFile);

    UserDto john = new UserDto().setLogin("john").setName("John").setEmail("john@email.com");
    db.userDao().insert(session, john);

    session.commit();

    MockUserSession.set().setLogin("john").addComponentPermission(UserRole.CODEVIEWER, project.getKey(), file.getKey()).setGlobalPermissions(GlobalPermissions.SYSTEM_ADMIN);
  }

  @After
  public void after() {
    session.close();
  }

  @Test
  public void define_action() throws Exception {
    WebService.Controller controller = wsTester.controller("api/issues");

    WebService.Action show = controller.action("search");
    assertThat(show).isNotNull();
    assertThat(show.handler()).isNotNull();
    assertThat(show.since()).isEqualTo("3.6");
    assertThat(show.isPost()).isFalse();
    assertThat(show.isInternal()).isFalse();
    assertThat(show.responseExampleAsString()).isNotEmpty();
    assertThat(show.params()).hasSize(37);
  }

  @Test
  public void empty_search() throws Exception {
    WsTester.TestRequest request = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION);
    WsTester.Result result = request.execute();

    assertThat(result).isNotNull();
    result.assertJson(this.getClass(), "empty_result.json", false);
  }

  @Test
  public void issue() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setDebt(10L)
      .setStatus("OPEN").setResolution("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR")
      .setAuthorLogin("John")
      .setAssignee("simon")
      .setReporter("fabrice")
      .setActionPlanKey("AP-ABCD")
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"));
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    // TODO date assertion is complex to test, and components id are not predictable, that's why strict boolean is set to false
    result.assertJson(this.getClass(), "issue.json", false);
  }

  @Test
  public void issues_on_different_projects() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));

    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setStatus("OPEN").setResolution("OPEN")
      .setSeverity("MAJOR")
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"));
    db.issueDao().insert(session, issue);

    ComponentDto project2 = ComponentTesting.newProjectDto().setUuid("DBCA").setProjectUuid("DBCA")
      .setKey("MyProject2");
    db.componentDao().insert(session, project2);
    db.snapshotDao().insert(session, SnapshotTesting.createForProject(project2));
    session.commit();

    tester.get(InternalPermissionService.class)
      .addPermission(new PermissionChange().setComponentKey(project2.getKey()).setGroup(DefaultGroups.ANYONE).setPermission(UserRole.USER));
    tester.get(InternalPermissionService.class).addPermission(
      new PermissionChange().setComponentKey(project2.getKey()).setGroup(DefaultGroups.ANYONE).setPermission(UserRole.CODEVIEWER));

    ComponentDto file2 = ComponentTesting.newFileDto(project2).setUuid("EDCB")
      .setKey("MyComponent2")
      .setParentProjectId(project2.getId());
    db.componentDao().insert(session, file2);

    IssueDto issue2 = IssueTesting.newDto(rule, file2, project2)
      .setKee("92fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setStatus("OPEN").setResolution("OPEN")
      .setSeverity("MAJOR")
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"));
    db.issueDao().insert(session, issue2);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    result.assertJson(this.getClass(), "issues_on_different_projects.json", false);
  }

  @Test
  public void issue_with_comment() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    IssueDto issue = IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);

    tester.get(IssueChangeDao.class).insert(session,
      new IssueChangeDto().setIssueKey(issue.getKey())
        .setKey("COMMENT-ABCD")
        .setChangeData("*My comment*")
        .setChangeType(IssueChangeDto.TYPE_COMMENT)
        .setUserLogin("john")
        .setCreatedAt(DateUtils.parseDate("2014-09-09")));
    tester.get(IssueChangeDao.class).insert(session,
      new IssueChangeDto().setIssueKey(issue.getKey())
        .setKey("COMMENT-ABCE")
        .setChangeData("Another comment")
        .setChangeType(IssueChangeDto.TYPE_COMMENT)
        .setUserLogin("fabrice")
        .setCreatedAt(DateUtils.parseDate("2014-09-10")));
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    result.assertJson(this.getClass(), "issue_with_comment.json", false);
  }

  @Test
  public void issue_with_action_plan() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    tester.get(ActionPlanDao.class).save(new ActionPlanDto()
      .setKey("AP-ABCD")
      .setName("1.0")
      .setStatus("OPEN")
      .setProjectId(project.getId())
      .setUserLogin("simon")
      .setDeadLine(DateUtils.parseDateTime("2014-01-24T19:10:03+0100"))
      .setCreatedAt(DateUtils.parseDateTime("2014-01-22T19:10:03+0100"))
      .setUpdatedAt(DateUtils.parseDateTime("2014-01-23T19:10:03+0100")));

    IssueDto issue = IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setActionPlanKey("AP-ABCD");
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    result.assertJson(this.getClass(), "issue_with_action_plan.json", false);
  }

  @Test
  public void issue_with_attributes() throws Exception {
    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setIssueAttributes(KeyValueFormat.format(ImmutableMap.of("jira-issue-key", "SONAR-1234")));
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    result.assertJson(this.getClass(), "issue_with_attributes.json", false);
  }

  @Test
  public void issue_with_extra_fields() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    tester.get(ActionPlanDao.class).save(new ActionPlanDto()
      .setKey("AP-ABCD")
      .setName("1.0")
      .setStatus("OPEN")
      .setProjectId(project.getId())
      .setUserLogin("simon"));

    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setAuthorLogin("John")
      .setAssignee("simon")
      .setReporter("fabrice")
      .setActionPlanKey("AP-ABCD");
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam("extra_fields", "actions,transitions,assigneeName,reporterName,actionPlanName").execute();
    result.assertJson(this.getClass(), "issue_with_extra_fields.json", false);
  }

  @Test
  public void issue_linked_on_removed_file() throws Exception {
    ComponentDto removedFile = ComponentTesting.newFileDto(project).setUuid("EDCB")
      .setEnabled(false)
      .setKey("RemovedComponent")
      .setParentProjectId(project.getId());
    db.componentDao().insert(session, removedFile);

    IssueDto issue = IssueTesting.newDto(rule, removedFile, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setComponent(removedFile)
      .setStatus("OPEN").setResolution("OPEN")
      .setSeverity("MAJOR")
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"));
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    result.assertJson(this.getClass(), "issue_linked_on_removed_file.json", false);
  }

  @Test
  public void issue_contains_component_id_for_eclipse() throws Exception {
    IssueDto issue = IssueTesting.newDto(rule, file, project);
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    assertThat(result.outputAsString()).contains("\"componentId\":" + file.getId() + ",");
  }

  @Test
  public void search_by_project_uuid() throws Exception {
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2"));
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam(IssueFilterParameters.PROJECT_UUIDS, project.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_project_uuid.json", false);

    wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam(IssueFilterParameters.PROJECT_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json", false);
  }

  @Test
  public void search_by_component_uuid() throws Exception {
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2"));
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam(IssueFilterParameters.COMPONENT_UUIDS, file.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_file_uuid.json", false);

    wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam(IssueFilterParameters.COMPONENT_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json", false);
  }

  @Test
  public void ignore_paging_with_one_component() throws Exception {
    for (int i = 0; i < QueryContext.MAX_LIMIT + 1; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      tester.get(IssueDao.class).insert(session, issue);
    }
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam(IssueFilterParameters.COMPONENTS, file.getKey())
      .setParam(IssueFilterParameters.IGNORE_PAGING, "true")
      .execute();
    result.assertJson(this.getClass(), "ignore_paging_with_one_component.json", false);
  }

  @Test
  public void apply_paging_with_multiple_components() throws Exception {
    for (int i = 0; i < QueryContext.MAX_LIMIT + 1; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      tester.get(IssueDao.class).insert(session, issue);
    }
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam(IssueFilterParameters.COMPONENTS, file.getKey() + "," + otherFile.getKey())
      .setParam(IssueFilterParameters.IGNORE_PAGING, "true")
      .execute();
    result.assertJson(this.getClass(), "apply_paging_with_multiple_components.json", false);
  }

  @Test
  public void apply_paging_with_one_component() throws Exception {
    for (int i = 0; i < QueryContext.MAX_LIMIT + 1; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      tester.get(IssueDao.class).insert(session, issue);
    }
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).setParam(IssueFilterParameters.COMPONENTS, file.getKey()).execute();
    result.assertJson(this.getClass(), "apply_paging_with_one_component.json", false);
  }

  @Test
  public void components_contains_sub_projects() throws Exception {
    ComponentDto project = ComponentTesting.newProjectDto().setKey("ProjectHavingModule");
    db.componentDao().insert(session, project);
    SnapshotDto projectSnapshot = SnapshotTesting.createForProject(project);
    db.snapshotDao().insert(session, projectSnapshot);
    session.commit();

    // project can be seen by anyone
    tester.get(InternalPermissionService.class).addPermission(new PermissionChange().setComponentKey(project.getKey()).setGroup(DefaultGroups.ANYONE).setPermission(UserRole.USER));

    ComponentDto module = ComponentTesting.newFileDto(project).setKey("ModuleHavingFile")
      .setScope("PRJ")
      .setParentProjectId(project.getId());
    db.componentDao().insert(session, module);
    db.snapshotDao().insert(session, SnapshotTesting.createForComponent(module, projectSnapshot));

    ComponentDto file = ComponentTesting.newFileDto(module).setKey("FileLinkedToModule");
    db.componentDao().insert(session, file);
    db.snapshotDao().insert(session, SnapshotTesting.createForComponent(file, projectSnapshot));

    IssueDto issue = IssueTesting.newDto(rule, file, project);
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).execute();
    result.assertJson(this.getClass(), "components_contains_sub_projects.json", false);
  }

  @Test
  public void display_facets() throws Exception {
    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setDebt(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam("resolved", "false")
      .setParam(SearchAction.PARAM_FACETS, "statuses,severities,resolutions,projectUuids,rules,componentUuids,assignees,languages,actionPlans")
      .execute();
    result.assertJson(this.getClass(), "display_facets.json", false);
  }

  @Test
  public void display_zero_valued_facets_for_selected_items() throws Exception {
    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setDebt(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam("resolved", "false")
      .setParam("severities", "MAJOR,MINOR")
      .setParam("languages", "xoo,polop,palap")
      .setParam(SearchAction.PARAM_FACETS, "statuses,severities,resolutions,projectUuids,rules,componentUuids,assignees,languages,actionPlans")
      .execute();
    result.assertJson(this.getClass(), "display_zero_facets.json", false);
  }

  @Test
  public void hide_rules() throws Exception {
    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setDebt(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION).setParam(IssueFilterParameters.HIDE_RULES, "true").execute();
    result.assertJson(this.getClass(), "hide_rules.json", false);
  }

  @Test
  public void sort_by_updated_at() throws Exception {
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac1").setIssueUpdateDate(DateUtils.parseDate("2014-11-02")));
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2").setIssueUpdateDate(DateUtils.parseDate("2014-11-01")));
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac3").setIssueUpdateDate(DateUtils.parseDate("2014-11-03")));
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.Result result = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION)
      .setParam("sort", IssueQuery.SORT_BY_UPDATE_DATE)
      .setParam("asc", "false")
      .execute();
    result.assertJson(this.getClass(), "sort_by_updated_at.json", false);
  }

  @Test
  public void paging() throws Exception {
    for (int i = 0; i < 12; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      tester.get(IssueDao.class).insert(session, issue);
    }
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.TestRequest request = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION);
    request.setParam(SearchAction.PARAM_PAGE, "2");
    request.setParam(SearchAction.PARAM_PAGE_SIZE, "9");

    WsTester.Result result = request.execute();
    result.assertJson(this.getClass(), "paging.json", false);
  }

  @Test
  public void paging_with_page_size_to_minus_one() throws Exception {
    for (int i = 0; i < 12; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      tester.get(IssueDao.class).insert(session, issue);
    }
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.TestRequest request = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION);
    request.setParam(SearchAction.PARAM_PAGE, "1");
    request.setParam(SearchAction.PARAM_PAGE_SIZE, "-1");

    WsTester.Result result = request.execute();
    result.assertJson(this.getClass(), "paging_with_page_size_to_minus_one.json", false);
  }

  @Test
  public void deprecated_paging() throws Exception {
    for (int i = 0; i < 12; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      tester.get(IssueDao.class).insert(session, issue);
    }
    session.commit();
    tester.get(IssueIndexer.class).indexAll();

    WsTester.TestRequest request = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION);
    request.setParam(IssueFilterParameters.PAGE_INDEX, "2");
    request.setParam(IssueFilterParameters.PAGE_SIZE, "9");

    WsTester.Result result = request.execute();
    result.assertJson(this.getClass(), "deprecated_paging.json", false);
  }

  @Test
  public void default_page_size_is_100() throws Exception {
    WsTester.TestRequest request = wsTester.newGetRequest(IssuesWs.API_ENDPOINT, SearchAction.SEARCH_ACTION);

    WsTester.Result result = request.execute();
    result.assertJson(this.getClass(), "default_page_size_is_100.json", false);
  }
}
