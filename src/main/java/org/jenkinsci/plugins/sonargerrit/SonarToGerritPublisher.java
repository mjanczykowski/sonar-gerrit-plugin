package org.jenkinsci.plugins.sonargerrit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.sonyericsson.hudson.plugins.gerrit.trigger.GerritManagement;
import com.sonyericsson.hudson.plugins.gerrit.trigger.config.IGerritHudsonTriggerConfig;
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritTrigger;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.gerrit.client.rest.GerritRestApiFactory;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.sonargerrit.data.SonarReportBuilder;
import org.jenkinsci.plugins.sonargerrit.data.converter.CustomIssueFormatter;
import org.jenkinsci.plugins.sonargerrit.data.converter.CustomReportFormatter;
import org.jenkinsci.plugins.sonargerrit.data.entity.Component;
import org.jenkinsci.plugins.sonargerrit.data.entity.Issue;
import org.jenkinsci.plugins.sonargerrit.data.entity.Report;
import org.jenkinsci.plugins.sonargerrit.data.entity.Severity;
import org.jenkinsci.plugins.sonargerrit.data.predicates.ByMinSeverityPredicate;
import org.jenkinsci.plugins.sonargerrit.data.predicates.ByNewPredicate;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.sonargerrit.util.Localization.getLocalized;

/**
 * Project: Sonar-Gerrit Plugin
 * Author:  Tatiana Didik
 */

public class SonarToGerritPublisher extends Publisher {

    private static final String DEFAULT_SONAR_REPORT_PATH = "target/sonar/sonar-report.json";
    private static final String DEFAULT_PROJECT_PATH = "";
    private static final String DEFAULT_SONAR_URL = "http://localhost:9000";
    private static final String DEFAULT_CATEGORY = "Code-Review";
    private static final int DEFAULT_SCORE = 0;
    private static final ReviewInput.NotifyHandling DEFAULT_NOTIFICATION_NO_ISSUES = ReviewInput.NotifyHandling.NONE;
    private static final ReviewInput.NotifyHandling DEFAULT_NOTIFICATION_ISSUES = ReviewInput.NotifyHandling.OWNER;

    public static final String GERRIT_FILE_DELIMITER = "/";
    public static final String EMPTY_STR = "";

    private static final Logger LOGGER = Logger.getLogger(SonarToGerritPublisher.class.getName());
    public static final String GERRIT_CHANGE_NUMBER_ENV_VAR_NAME = "GERRIT_CHANGE_NUMBER";
    public static final String GERRIT_NAME_ENV_VAR_NAME = "GERRIT_NAME";
    public static final String GERRIT_PATCHSET_NUMBER_ENV_VAR_NAME = "GERRIT_PATCHSET_NUMBER";

    // left here for compatibility with previous version. will be removed in further releases
    private final String path;
    private final String projectPath;

    private final String sonarURL;
    private List<SubJobConfig> subJobConfigs;
    private final String severity;
    private final boolean changedLinesOnly;
    private final boolean newIssuesOnly;
    private final String noIssuesToPostText;
    private final String someIssuesToPostText;
    private final String issueComment;
    private final boolean postScore;
    private final String category;
    private final String noIssuesScore;
    private final String issuesScore;

    private final String noIssuesNotification;
    private final String issuesNotification;


    @DataBoundConstructor
    public SonarToGerritPublisher(String sonarURL, List<SubJobConfig> subJobConfigs,
                                  String severity, boolean changedLinesOnly, boolean newIssuesOnly,
                                  String noIssuesToPostText, String someIssuesToPostText, String issueComment,
                                  boolean postScore, String category, String noIssuesScore, String issuesScore,
                                  String noIssuesNotification, String issuesNotification) {
        this.sonarURL = MoreObjects.firstNonNull(sonarURL, DEFAULT_SONAR_URL);
        this.subJobConfigs = subJobConfigs;
        this.severity = MoreObjects.firstNonNull(severity, Severity.MAJOR.name());
        this.changedLinesOnly = changedLinesOnly;
        this.newIssuesOnly = newIssuesOnly;
        this.noIssuesToPostText = noIssuesToPostText;
        this.someIssuesToPostText = someIssuesToPostText;
        this.issueComment = issueComment;
        this.postScore = postScore;
        this.category = MoreObjects.firstNonNull(category, DEFAULT_CATEGORY);
        this.noIssuesScore = noIssuesScore;
        this.issuesScore = issuesScore;
        this.noIssuesNotification = noIssuesNotification;
        this.issuesNotification = issuesNotification;

        // old values - not used anymore. will be deleted in further releases
        this.path = null;
        this.projectPath = null;
    }


    public String getSeverity() {
        return severity;
    }

    public boolean isChangedLinesOnly() {
        return changedLinesOnly;
    }

    public boolean isNewIssuesOnly() {
        return newIssuesOnly;
    }

    public String getSonarURL() {
        return sonarURL;
    }

    public String getNoIssuesToPostText() {
        return noIssuesToPostText;
    }

    public String getSomeIssuesToPostText() {
        return someIssuesToPostText;
    }

    public String getIssueComment() {
        return issueComment;
    }

    @SuppressWarnings(value = "unused")
    public boolean isPostScore() {
        return postScore;
    }

    public String getCategory() {
        return category;
    }

    @SuppressWarnings(value = "unused")
    public String getNoIssuesScore() {
        return noIssuesScore;
    }

    @SuppressWarnings(value = "unused")
    public String getNoIssuesNotification() {
        return noIssuesNotification;
    }

    @SuppressWarnings(value = "unused")
    public String getIssuesNotification() {
        return issuesNotification;
    }

    @SuppressWarnings(value = "unused")
    public String getIssuesScore() {
        return issuesScore;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException,
            InterruptedException {

        List<ReportInfo> issueInfos = readSonarReports(listener, build.getWorkspace());
        if (issueInfos == null) {
            logMessage(listener, "jenkins.plugin.validation.path.no.project.config.available", Level.SEVERE);
            return false;
        }

        Multimap<String, Issue> file2issues = generateFilenameToIssuesMapFilteredByPredicates(issueInfos);

        // Step 3 - Prepare Gerrit REST API client
        // Check Gerrit configuration is available
        String gerritNameEnvVar = getEnvVar(build, listener, GERRIT_NAME_ENV_VAR_NAME);
        GerritTrigger trigger = GerritTrigger.getTrigger(build.getProject());
        String gerritServerName = gerritNameEnvVar != null ? gerritNameEnvVar : trigger != null ? trigger.getServerName() : null;
        if (gerritServerName == null) {
            logMessage(listener, "jenkins.plugin.error.gerrit.server.empty", Level.SEVERE);
            return false;
        }
        IGerritHudsonTriggerConfig gerritConfig = GerritManagement.getConfig(gerritServerName);
        if (gerritConfig == null) {
            logMessage(listener, "jenkins.plugin.error.gerrit.config.empty", Level.SEVERE);
            return false;
        }

        if (!gerritConfig.isUseRestApi()) {
            logMessage(listener, "jenkins.plugin.error.gerrit.restapi.off", Level.SEVERE);
            return false;
        }
        if (gerritConfig.getGerritHttpUserName() == null) {
            logMessage(listener, "jenkins.plugin.error.gerrit.user.empty", Level.SEVERE);
            return false;
        }
        GerritRestApiFactory gerritRestApiFactory = new GerritRestApiFactory();
        GerritAuthData.Basic authData = new GerritAuthData.Basic(gerritConfig.getGerritFrontEndUrl(),
                gerritConfig.getGerritHttpUserName(), gerritConfig.getGerritHttpPassword());
        GerritApi gerritApi = gerritRestApiFactory.create(authData);
        try {
            int changeNumber = Integer.parseInt(getEnvVar(build, listener, GERRIT_CHANGE_NUMBER_ENV_VAR_NAME));
            int patchSetNumber = Integer.parseInt(getEnvVar(build, listener, GERRIT_PATCHSET_NUMBER_ENV_VAR_NAME));
            RevisionApi revision = gerritApi.changes().id(changeNumber).revision(patchSetNumber);
            logMessage(listener, "jenkins.plugin.connected.to.gerrit", Level.INFO, new Object[]{gerritServerName, changeNumber, patchSetNumber});

            // Step 4 - Filter issues by changed files
            final Map<String, FileInfo> files = revision.files();
            file2issues = Multimaps.filterKeys(file2issues, new Predicate<String>() {
                @Override
                public boolean apply(@Nullable String input) {
                    return input != null && files.keySet().contains(input);
                }
            });

//            logResultMap(file2issues, "Filter issues by changed files: {0} elements");

            if (isChangedLinesOnly()) {
                // Step 4a - Filter issues by changed lines in file only
                filterIssuesByChangedLines(file2issues, revision);
//                logResultMap(file2issues, "Filter issues by changed lines: {0} elements");
            }

            // Step 6 - Send review to Gerrit
            ReviewInput reviewInput = getReviewResult(file2issues);

            // Step 7 - Post review
            revision.review(reviewInput);
            logMessage(listener, "jenkins.plugin.review.sent", Level.INFO);
        } catch (RestApiException e) {
            listener.getLogger().println("Unable to post review: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "Unable to post review: " + e.getMessage(), e);
            return false;
        }

        return true;
    }

    @VisibleForTesting
    Multimap<String, Issue> generateFilenameToIssuesMapFilteredByPredicates(List<ReportInfo> issueInfos) {
        Multimap<String, Issue> file2issues = LinkedListMultimap.create();
        for (ReportInfo info : issueInfos) {

            Report report = info.report;

            // Step 1 - Filter issues by issues only predicates
            Iterable<Issue> filtered = filterIssuesByPredicates(report.getIssues());

            // Step 2 - Calculate real file name for issues and store to multimap
            file2issues.putAll(generateFilenameToIssuesMapFilteredByPredicates(info.directoryPath, report, filtered));
        }
        return file2issues;
    }

    private Report readSonarReport(BuildListener listener, FilePath workspace, SubJobConfig config) throws IOException,
            InterruptedException {
        FilePath reportPath = workspace.child(config.getSonarReportPath());
        if (!reportPath.exists()) {
            logMessage(listener, "jenkins.plugin.error.sonar.report.not.exists", Level.SEVERE, reportPath);
            return null;
        }
        logMessage(listener, "jenkins.plugin.getting.report", Level.INFO, reportPath);

        SonarReportBuilder builder = new SonarReportBuilder();
        String reportJson = reportPath.readToString();
        Report report = builder.fromJson(reportJson);
        logMessage(listener, "jenkins.plugin.report.loaded", Level.INFO, report.getIssues().size());
        return report;
    }

    @VisibleForTesting
    List<ReportInfo> readSonarReports(BuildListener listener, FilePath workspace) throws IOException,
            InterruptedException {
        List<ReportInfo> reports = new ArrayList<ReportInfo>();
        for (SubJobConfig subJobConfig : getSubJobConfigs(false)) { // to be replaced by this.subJobConfigs in further releases - this code is to support older versions
            Report report = readSonarReport(listener, workspace, subJobConfig);
            if (report == null) {
                return null;
            }
            reports.add(new ReportInfo(subJobConfig.getProjectPath(), report));
        }
        return reports;
    }

    private String getEnvVar(AbstractBuild build, BuildListener listener, String name) throws IOException, InterruptedException {
        EnvVars envVars = build.getEnvironment(listener);
        return envVars.get(name);
    }

    private void logMessage(BuildListener listener, String message, Level l, Object... params) {
        message = getLocalized(message, params);
        if (listener != null) {     // it can be it tests
            listener.getLogger().println(message);
        }
        LOGGER.log(l, message);
    }

    private String getReviewMessage(Multimap<String, Issue> finalIssues) {
        return new CustomReportFormatter(finalIssues.values(), someIssuesToPostText, noIssuesToPostText).getMessage();
    }

    private int getReviewMark(int finalIssuesCount) {
        String mark = finalIssuesCount > 0 ? issuesScore : noIssuesScore;
        return parseNumber(mark, DEFAULT_SCORE);
    }

    public List<SubJobConfig> getSubJobConfigs() {
        return getSubJobConfigs(true);
    }

    public List<SubJobConfig> getSubJobConfigs(boolean addDefault) {
        if (subJobConfigs == null) {
            subJobConfigs = new ArrayList<SubJobConfig>();
            // add configuration from previous plugin version
            if (path != null || projectPath != null) {
                subJobConfigs.add(new SubJobConfig(projectPath, path));
            } else if (addDefault) {
                subJobConfigs.add(new SubJobConfig(DEFAULT_PROJECT_PATH, DEFAULT_SONAR_REPORT_PATH));
            }
        }
        return subJobConfigs;
    }

    private ReviewInput.NotifyHandling getNotificationSettings(int finalIssuesCount) {
        if (finalIssuesCount > 0) {
            ReviewInput.NotifyHandling value = (issuesNotification == null ? null : ReviewInput.NotifyHandling.valueOf(issuesNotification));
            return MoreObjects.firstNonNull(value, DEFAULT_NOTIFICATION_ISSUES);
        } else {
            ReviewInput.NotifyHandling value = (noIssuesNotification == null ? null : ReviewInput.NotifyHandling.valueOf(noIssuesNotification));
            return MoreObjects.firstNonNull(value, DEFAULT_NOTIFICATION_NO_ISSUES);
        }
    }

    private int parseNumber(String number, int deflt) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return deflt;
        }

    }

    @VisibleForTesting
    ReviewInput getReviewResult(Multimap<String, Issue> finalIssues) {
        String reviewMessage = getReviewMessage(finalIssues);
        ReviewInput reviewInput = new ReviewInput().message(reviewMessage);

        int finalIssuesCount = finalIssues.size();

        reviewInput.notify = getNotificationSettings(finalIssuesCount);

        if (postScore) {
            reviewInput.label(category, getReviewMark(finalIssuesCount));
        }

        reviewInput.comments = new HashMap<String, List<ReviewInput.CommentInput>>();
        for (String file : finalIssues.keySet()) {
            reviewInput.comments.put(file, Lists.newArrayList(
                            Collections2.transform(finalIssues.get(file),
                                    new Function<Issue, ReviewInput.CommentInput>() {
                                        @Nullable
                                        @Override
                                        public ReviewInput.CommentInput apply(@Nullable Issue input) {
                                            if (input == null) {
                                                return null;
                                            }
                                            ReviewInput.CommentInput commentInput = new ReviewInput.CommentInput();
                                            commentInput.id = input.getKey();
                                            commentInput.line = input.getLine();
                                            commentInput.message = new CustomIssueFormatter(input, issueComment, getSonarURL()).getMessage();
                                            return commentInput;
                                        }

                                    }
                            )
                    )
            );
        }
        return reviewInput;
    }

    @VisibleForTesting
    void filterIssuesByChangedLines(Multimap<String, Issue> finalIssues, RevisionApi revision) throws RestApiException {
        for (String filename : new HashSet<String>(finalIssues.keySet())) {
            List<DiffInfo.ContentEntry> content = revision.file(filename).diff().content;
            int processed = 0;
//            final RangeSet<Integer> rangeSet = TreeRangeSet.create();
            Set<Integer> rangeSet = new HashSet<Integer>();
            for (DiffInfo.ContentEntry contentEntry : content) {
                if (contentEntry.ab != null) {
                    processed += contentEntry.ab.size();
                } else if (contentEntry.b != null) {
                    int start = processed + 1;
                    int end = processed + contentEntry.b.size();
                    for (int i = start; i <= end; i++) {    // todo use guava Range for this purpose
                        rangeSet.add(i);
                    }
//                    rangeSet.add(Range.closed(start, end));
                    processed += contentEntry.b.size();
                }
            }

            Collection<Issue> issues = new ArrayList<Issue>(finalIssues.get(filename));
            for (Issue i : issues) {
                if (!rangeSet.contains(i.getLine())) {
                    finalIssues.get(filename).remove(i);
                }
            }
        }
    }

    @VisibleForTesting
    Multimap<String, Issue> generateFilenameToIssuesMapFilteredByPredicates(String projectPath, Report report, Iterable<Issue> filtered) {
        Multimap<String, Issue> file2issues = LinkedListMultimap.create();

/*       The next code prepares data to process situations like this one:
        {
            "key": "com.maxifier.guice:guice-bootstrap",
            "path": "guice-bootstrap"
        },
        {
            "key": "com.maxifier.guice:guice-bootstrap:src/main/java/com/magenta/guice/bootstrap/plugins/ChildModule.java",
            "path": "src/main/java/com/magenta/guice/bootstrap/plugins/ChildModule.java",
            "moduleKey": "com.maxifier.guice:guice-bootstrap",
            "status": "SAME"
        }
        */
        Map<String, String> component2module = Maps.newHashMap();
        Map<String, String> component2path = Maps.newHashMap();

        for (Component component : report.getComponents()) {
            component2path.put(component.getKey(), component.getPath());
        }
        for (Component component : report.getComponents()) {
            if (component.getModuleKey() != null) {
                component2module.put(component.getKey(), component2path.get(component.getModuleKey()));
            }
        }


        // generating map consisting of real file names to corresponding issues collections.
        for (Issue issue : filtered) {
            String issueComponent = issue.getComponent();
            String moduleName = component2module.get(issueComponent);
            String componentPath = component2path.get(issueComponent);

            String realFileName = appendDelimiter(projectPath) + appendDelimiter(moduleName) + componentPath;
            file2issues.put(realFileName, issue);

        }
        return file2issues;
    }

    private String appendDelimiter(String subPath) {
        return subPath == null || subPath.trim().isEmpty() ? EMPTY_STR : subPath.endsWith(GERRIT_FILE_DELIMITER) ? subPath : subPath + GERRIT_FILE_DELIMITER;
    }

    @VisibleForTesting
    Iterable<Issue> filterIssuesByPredicates(List<Issue> issues) {
        Severity sev = Severity.valueOf(severity);
        return Iterables.filter(issues,
                Predicates.and(
                        ByMinSeverityPredicate.apply(sev),
                        ByNewPredicate.apply(isNewIssuesOnly()))
        );
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @VisibleForTesting
    class ReportInfo {
        private String directoryPath;
        private Report report;

        public ReportInfo(String directoryPath, Report report) {
            this.directoryPath = directoryPath;
            this.report = report;
        }
    }

    /**
     * Descriptor for {@link SonarToGerritPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * <p/>
     * <p/>
     * See <tt>src/main/resources/hudson/plugins/hello_world/SonarToGerritBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'sonarURL'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckSonarURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0) {
                return FormValidation.warning(getLocalized("jenkins.plugin.validation.sonar.url.empty"));
            }
            try {
                new URL(value);
            } catch (MalformedURLException e) {
                return FormValidation.warning(getLocalized("jenkins.plugin.validation.sonar.url.invalid"));
            }
            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'noIssuesToPostText'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckNoIssuesToPostText(@QueryParameter String value) {
            if (value.length() == 0) {
                return FormValidation.error(getLocalized("jenkins.plugin.validation.review.title.empty"));
            }
            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'someIssuesToPostText'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckSomeIssuesToPostText(@QueryParameter String value) {
            if (value.length() == 0) {
                return FormValidation.error(getLocalized("jenkins.plugin.validation.review.title.empty"));
            }
            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'issueComment'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckIssueComment(@QueryParameter String value) {
            if (value.length() == 0) {
                return FormValidation.error(getLocalized("jenkins.plugin.validation.review.body.empty"));
            }
            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'severity'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckSeverity(@QueryParameter String value) {
            if (value == null || Severity.valueOf(value) == null) {
                return FormValidation.error(getLocalized("jenkins.plugin.validation.review.severity.unknown"));
            }
            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'noIssuesScore'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckNoIssuesScore(@QueryParameter String value) {
            return checkScore(value);
        }

        /**
         * Performs on-the-fly validation of the form field 'issuesScore'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckIssuesScore(@QueryParameter String value) {
            return checkScore(value);
        }

        private FormValidation checkScore(@QueryParameter String value) {
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return FormValidation.error(getLocalized("jenkins.plugin.validation.review.score.not.numeric"));
            }
            return FormValidation.ok();
        }

        /**
         * Performs on-the-fly validation of the form field 'noIssuesNotification'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckNoIssuesNotification(@QueryParameter String value) {
            return checkNotificationType(value);
        }

        /**
         * Performs on-the-fly validation of the form field 'issuesNotification'.
         *
         * @param value This parameter receives the value that the user has typed.
         * @return Indicates the outcome of the validation. This is sent to the browser.
         * <p/>
         * Note that returning {@link FormValidation#error(String)} does not
         * prevent the form from being saved. It just means that a message
         * will be displayed to the user.
         */
        @SuppressWarnings(value = "unused")
        public FormValidation doCheckIssuesNotification(@QueryParameter String value) {
            return checkNotificationType(value);
        }

        private FormValidation checkNotificationType(@QueryParameter String value) {
            if (value == null || ReviewInput.NotifyHandling.valueOf(value) == null) {
                return FormValidation.error(getLocalized("jenkins.plugin.validation.review.notification.recipient.unknown"));
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return getLocalized("jenkins.plugin.build.step.name");
        }

    }
}
