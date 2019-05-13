/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization

import org.gradle.BuildListener
import org.gradle.StartParameter
import org.gradle.api.Task
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.SettingsInternal
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectRegistry
import org.gradle.composite.internal.IncludedBuildControllers
import org.gradle.configuration.BuildConfigurer
import org.gradle.execution.BuildExecuter
import org.gradle.execution.MultipleBuildFailures
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal
import org.gradle.initialization.exception.ExceptionAnalyser
import org.gradle.internal.concurrent.ParallelismConfigurationManagerFixture
import org.gradle.internal.concurrent.Stoppable
import org.gradle.internal.execution.history.ExecutionHistoryCacheAccess
import org.gradle.internal.operations.TestBuildOperationExecutor
import org.gradle.internal.resources.DefaultResourceLockCoordinationService
import org.gradle.internal.resources.ResourceLockCoordinationService
import org.gradle.internal.service.ServiceRegistry
import org.gradle.internal.service.scopes.BuildScopeServices
import org.gradle.internal.work.DefaultWorkerLeaseService
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.TestUtil
import spock.lang.Specification

import static org.gradle.util.Path.path

class DefaultGradleLauncherSpec extends Specification {
    def settingsPreparerMock = Mock(SettingsPreparer)
    def taskExecutionPreparerMock = Mock(TaskExecutionPreparer)
    def taskGraphMock = Mock(TaskExecutionGraphInternal)
    def buildConfigurerMock = Mock(BuildConfigurer)
    def buildBroadcaster = Mock(BuildListener)
    def buildExecuter = Mock(BuildExecuter)
    def buildScopeServices = Mock(ServiceRegistry)
    def cacheAccess = Mock(ExecutionHistoryCacheAccess)

    private ProjectInternal expectedRootProject
    private ProjectInternal expectedCurrentProject
    private StartParameter expectedStartParams
    private SettingsInternal settingsMock = Mock(SettingsInternal.class)
    private GradleInternal gradleMock = Mock(GradleInternal.class)

    private ProjectDescriptor expectedRootProjectDescriptor

    private ClassLoaderScope baseClassLoaderScope = Mock(ClassLoaderScope.class)
    private ExceptionAnalyser exceptionAnalyserMock = Mock(ExceptionAnalyser)
    private BuildCompletionListener buildCompletionListener = Mock(BuildCompletionListener.class)
    private TestBuildOperationExecutor buildOperationExecutor = new TestBuildOperationExecutor()
    private ResourceLockCoordinationService coordinationService = new DefaultResourceLockCoordinationService()
    private WorkerLeaseService workerLeaseService = new DefaultWorkerLeaseService(coordinationService, new ParallelismConfigurationManagerFixture(true, 1))
    private BuildScopeServices buildServices = Mock(BuildScopeServices.class)
    private Stoppable otherService = Mock(Stoppable)
    private IncludedBuildControllers includedBuildControllers = Mock()
    private InstantExecution instantExecution = Mock()
    public TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    final RuntimeException failure = new RuntimeException("main")
    final RuntimeException transformedException = new RuntimeException("transformed")

    def setup() {
        boolean expectedSearchUpwards = false

        File expectedRootDir = tmpDir.file("rootDir")
        File expectedCurrentDir = new File(expectedRootDir, "currentDir")

        expectedRootProjectDescriptor = new DefaultProjectDescriptor(null, "someName", new File("somedir"), new DefaultProjectDescriptorRegistry(),
            TestFiles.resolver(expectedRootDir))
        expectedRootProject = TestUtil.createRootProject(expectedRootDir)
        expectedCurrentProject = TestUtil.createRootProject(expectedCurrentDir)

        expectedStartParams = new StartParameter()
        expectedStartParams.setCurrentDir(expectedCurrentDir)
        expectedStartParams.setSearchUpwards(expectedSearchUpwards)
        expectedStartParams.setGradleUserHomeDir(tmpDir.createDir("gradleUserHome"))

        _ * exceptionAnalyserMock.transform(failure) >> transformedException

        _ * settingsMock.getRootProject() >> expectedRootProjectDescriptor
        _ * settingsMock.getDefaultProject() >> expectedRootProjectDescriptor
        _ * settingsMock.getIncludedBuilds() >> []
        _ * settingsMock.getRootClassLoaderScope() >> baseClassLoaderScope
        _ * settingsMock.getProjectRegistry() >> Stub(ProjectRegistry)
        0 * settingsMock._

        _ * gradleMock.getRootProject() >> expectedRootProject
        _ * gradleMock.getDefaultProject() >> expectedCurrentProject
        _ * gradleMock.getTaskGraph() >> taskGraphMock
        _ * taskGraphMock.getRequestedTasks() >> [Mock(Task)]
        _ * taskGraphMock.getFilteredTasks() >> [Mock(Task)]
        _ * gradleMock.getStartParameter() >> expectedStartParams
        _ * gradleMock.getServices() >> buildScopeServices
        _ * gradleMock.includedBuilds >> []
        _ * gradleMock.getBuildOperation() >> null
        _ * gradleMock.settings >> settingsMock
        _ * gradleMock.buildListenerBroadcaster >> buildBroadcaster

        buildScopeServices.get(ExecutionHistoryCacheAccess) >> cacheAccess
        buildScopeServices.get(IncludedBuildControllers) >> includedBuildControllers
        buildServices.get(WorkerLeaseService) >> workerLeaseService
    }

    def cleanup() {
        workerLeaseService.stop()
    }

    DefaultGradleLauncher launcher() {
        return new DefaultGradleLauncher(gradleMock, buildConfigurerMock, exceptionAnalyserMock, buildBroadcaster,
            buildCompletionListener, buildOperationExecutor, buildExecuter, buildServices, [otherService], includedBuildControllers,
            settingsPreparerMock, taskExecutionPreparerMock, instantExecution)
    }

    void testRunTasks() {
        when:
        isRootBuild()
        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRun()
        expectBuildListenerCallbacks()
        DefaultGradleLauncher gradleLauncher = launcher()
        GradleInternal result = gradleLauncher.executeTasks()

        then:
        result == gradleMock
        expectedBuildOperationsFired()
    }

    void testRunAsNestedBuild() {
        when:
        isNestedBuild()

        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRun()
        expectBuildListenerCallbacks()
        DefaultGradleLauncher gradleLauncher = launcher()
        GradleInternal result = gradleLauncher.executeTasks()

        then:
        result == gradleMock

        and:
        assert buildOperationExecutor.operations.size() == 1
        assert buildOperationExecutor.operations[0].displayName == "Run tasks (:nested)"
    }

    void testGetBuildAnalysis() {
        when:
        isRootBuild()
        expectSettingsBuilt()
        expectBuildListenerCallbacks()

        1 * buildConfigurerMock.configure(gradleMock)

        DefaultGradleLauncher gradleLauncher = launcher()
        def result = gradleLauncher.getConfiguredBuild()

        then:
        result == gradleMock
    }

    void testNotifiesListenerOfBuildAnalysisStages() {
        when:
        isRootBuild()
        expectSettingsBuilt()
        expectBuildListenerCallbacks()
        1 * buildConfigurerMock.configure(gradleMock)

        then:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.getConfiguredBuild()
    }

    void testNotifiesListenerOfBuildStages() {
        when:
        isRootBuild()
        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRun()
        expectBuildListenerCallbacks()

        then:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.executeTasks()
    }

    void testNotifiesListenerOnBuildListenerFailure() {
        given:
        isRootBuild()
        1 * buildBroadcaster.buildStarted(gradleMock) >> { throw failure }
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })

        when:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.executeTasks()

        then:
        def t = thrown RuntimeException
        t == transformedException
    }

    void testNotifiesListenerOnSettingsInitWithFailure() {
        given:
        isRootBuild()

        and:
        1 * buildBroadcaster.buildStarted(gradleMock)
        1 * settingsPreparerMock.prepareSettings(gradleMock) >> { throw failure }
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })

        when:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.executeTasks()

        then:
        def t = thrown RuntimeException
        t == transformedException
    }

    void testNotifiesListenerOnBuildCompleteWithFailure() {
        given:
        isRootBuild()
        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRunWithFailure(failure)

        and:
        1 * buildBroadcaster.buildStarted(gradleMock)
        1 * exceptionAnalyserMock.transform({ it instanceof MultipleBuildFailures && it.cause == failure }) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })

        when:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.executeTasks()

        then:
        def t = thrown RuntimeException
        t == transformedException
    }

    void testNotifiesListenerOnBuildCompleteWithMultipleFailures() {
        def failure2 = new RuntimeException()

        given:
        isRootBuild()
        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRunWithFailure(failure, failure2)

        and:
        1 * buildBroadcaster.buildStarted(gradleMock)
        1 * exceptionAnalyserMock.transform({ it instanceof MultipleBuildFailures && it.causes == [failure, failure2] }) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })

        when:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.executeTasks()

        then:
        def t = thrown RuntimeException
        t == transformedException
    }

    void testTransformsBuildFinishedListenerFailure() {
        given:
        isRootBuild()
        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRun()

        and:
        1 * buildBroadcaster.buildStarted(gradleMock)
        1 * buildBroadcaster.buildFinished({ it.failure == null }) >> { throw failure }
        1 * exceptionAnalyserMock.transform({ it instanceof MultipleBuildFailures && it.cause == failure }) >> transformedException

        and:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.executeTasks()

        when:
        gradleLauncher.finishBuild()

        then:
        def t = thrown RuntimeException
        t == transformedException
    }

    void testNotifiesListenersOnMultipleBuildFailuresAndBuildListenerFailure() {
        def failure2 = new RuntimeException()
        def failure3 = new RuntimeException()
        def finalException = new RuntimeException()

        given:
        isRootBuild()
        expectSettingsBuilt()
        expectTaskGraphBuilt()
        expectTasksRunWithFailure(failure, failure2)

        and:
        1 * buildBroadcaster.buildStarted(gradleMock)
        1 * exceptionAnalyserMock.transform({ it instanceof MultipleBuildFailures && it.causes == [failure, failure2] }) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException }) >> { throw failure3 }
        1 * exceptionAnalyserMock.transform({ it instanceof MultipleBuildFailures && it.causes == [failure, failure2, failure3] }) >> finalException

        and:
        DefaultGradleLauncher gradleLauncher = launcher()

        when:
        gradleLauncher.executeTasks()

        then:
        def t = thrown RuntimeException
        t == finalException
    }

    void testCleansUpOnStop() throws IOException {
        when:
        DefaultGradleLauncher gradleLauncher = launcher()
        gradleLauncher.stop()

        then:
        1 * buildServices.close()
        1 * otherService.stop()
        1 * buildCompletionListener.completed()
    }

    private void expectedBuildOperationsFired() {
        assert buildOperationExecutor.operations.size() == 1
        assert buildOperationExecutor.operations[0].displayName == "Run tasks"
    }

    private void isNestedBuild() {
        _ * gradleMock.parent >> Mock(GradleInternal)
        _ * gradleMock.findIdentityPath() >> path(":nested")
        _ * gradleMock.contextualize(_) >> {"${it[0]} (:nested)"}
    }

    private void isRootBuild() {
        _ * gradleMock.parent >> null
        _ * gradleMock.contextualize(_) >> { it[0] }
    }

    private void expectSettingsBuilt() {
        1 * settingsPreparerMock.prepareSettings(gradleMock)
    }

    private void expectBuildListenerCallbacks() {
        1 * buildBroadcaster.buildStarted(gradleMock)
    }

    private void expectTaskGraphBuilt() {
        1 * taskExecutionPreparerMock.prepareForTaskExecution(gradleMock)
    }

    private void expectTasksRun() {
        1 * includedBuildControllers.startTaskExecution()
        1 * buildExecuter.execute(gradleMock, _)
        1 * includedBuildControllers.awaitTaskCompletion(_)
    }

    private void expectTasksRunWithFailure(Throwable failure, Throwable other = null) {
        1 * includedBuildControllers.startTaskExecution()
        1 * buildExecuter.execute(gradleMock, _) >> { GradleInternal g, List failures ->
            failures.add(failure)
        }
        1 * includedBuildControllers.awaitTaskCompletion(_) >> { List args ->
            if (other != null) {
                args[0].add(other)
            }
        }
        1 * includedBuildControllers.finishBuild(_)
    }
}
