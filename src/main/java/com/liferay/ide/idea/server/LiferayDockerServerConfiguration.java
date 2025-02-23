/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.ide.idea.server;

import com.intellij.diagnostic.logging.LogConfigurationPanel;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.CommonProgramRunConfigurationParameters;
import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.JavaRunConfigurationExtensionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.JavaRunConfigurationModule;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.configurations.SearchScopeProvidingRunProfile;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.util.ProgramParametersUtil;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunnableState;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.options.SettingsEditorGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.psi.search.ExecutionSearchScopes;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.xmlb.SkipEmptySerializationFilter;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;

import com.liferay.blade.gradle.tooling.ProjectInfo;
import com.liferay.ide.idea.core.LiferayCore;
import com.liferay.ide.idea.core.WorkspaceProvider;
import com.liferay.ide.idea.util.CoreUtil;
import com.liferay.ide.idea.util.GradleUtil;
import com.liferay.ide.idea.util.LiferayWorkspaceSupport;
import com.liferay.ide.idea.util.ListUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.SwingUtilities;

import org.gradle.tooling.model.GradleProject;

import org.jdom.Element;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.util.GradleConstants;

/**
 * @author Simon Jiang
 */
@SuppressWarnings("unchecked")
public class LiferayDockerServerConfiguration
	extends LocatableConfigurationBase
	implements CommonProgramRunConfigurationParameters, LiferayWorkspaceSupport, SearchScopeProvidingRunProfile {

	public LiferayDockerServerConfiguration(Project project, ConfigurationFactory factory, String name) {
		super(project, factory, name);

		_project = project;
		_factory = factory;
		_name = name;

		_javaRunConfigurationModule = new JavaRunConfigurationModule(project, true);

		_initDockerInfo();
	}

	@Override
	public void checkConfiguration() throws RuntimeConfigurationException {
		ProgramParametersUtil.checkWorkingDirectoryExist(this, getProject(), null);

		WorkspaceProvider workspaceProvider = LiferayCore.getWorkspaceProvider(_project);

		if (Objects.isNull(workspaceProvider)) {
			throw new RuntimeConfigurationException(
				"Can only create Liferay Docker server within liferay workspace project",
				"Invalid liferay workspace project type");
		}

		if (!workspaceProvider.isGradleWorkspace()) {
			throw new RuntimeConfigurationException(
				"Liferay Maven workspace not support docker", "Invalid liferay workspace project type");
		}

		if (CoreUtil.isNullOrEmpty(_liferayDockerServerConfig.dockerImageId) &&
			!_liferayDockerServerConfig.dockerImageId.startsWith("loading...")) {

			throw new RuntimeConfigurationException("Please set correct docker image id", "Invalid docker image id");
		}

		if (CoreUtil.isNullOrEmpty(_liferayDockerServerConfig.dockerContainerId) &&
			!_liferayDockerServerConfig.dockerContainerId.startsWith("loading...")) {

			throw new RuntimeConfigurationException(
				"Please set correct docker container id", "Invalid docker container id");
		}

		RunManager runManager = RunManager.getInstance(_project);

		List<RunConfiguration> configurationList = runManager.getAllConfigurationsList();

		for (RunConfiguration runConfiguration : configurationList) {
			ConfigurationType configurationType = runConfiguration.getType();

			if (Objects.equals(LiferayDockerServerConfigurationType.id, configurationType.getId())) {
				LiferayDockerServerConfiguration configuration = (LiferayDockerServerConfiguration)runConfiguration;

				if (Objects.equals(configuration.getDockerImageId(), _liferayDockerServerConfig.dockerImageId) &&
					!Objects.equals(configuration.getName(), getName())) {

					throw new RuntimeConfigurationException(
						"Another docker image has the same image id", "Invalid docker image id");
				}
			}
		}
	}

	@Override
	public LiferayDockerServerConfiguration clone() {
		LiferayDockerServerConfiguration clone = (LiferayDockerServerConfiguration)super.clone();

		_liferayDockerServerConfig.workspaceLocation = _project.getBasePath();

		clone.setConfig(XmlSerializerUtil.createCopy(_liferayDockerServerConfig));

		JavaRunConfigurationModule configurationModule = new JavaRunConfigurationModule(getProject(), true);

		configurationModule.setModule(_javaRunConfigurationModule.getModule());

		clone.setDockerImageId(_liferayDockerServerConfig.dockerImageId);

		clone.setDockerContainerId(_liferayDockerServerConfig.dockerContainerId);

		clone.setConfigurationModule(configurationModule);

		clone.setEnvs(new LinkedHashMap<>(clone.getEnvs()));

		return clone;
	}

	@NotNull
	@Override
	@Transient
	public List<BeforeRunTask<?>> getBeforeRunTasks() {
		return Collections.emptyList();
	}

	@NotNull
	@Override
	public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
		SettingsEditorGroup<LiferayDockerServerConfiguration> group = new SettingsEditorGroup<>();
		String title = ExecutionBundle.message("run.configuration.configuration.tab.title");

		group.addEditor(title, new LiferayDockerServerConfigurationEditor(getProject()));

		JavaRunConfigurationExtensionManager javaRunConfigurationExtensionManager =
			JavaRunConfigurationExtensionManager.getInstance();

		javaRunConfigurationExtensionManager.appendEditors(this, group);

		group.addEditor(ExecutionBundle.message("logs.tab.title"), new LogConfigurationPanel<>());

		return group;
	}

	public String getDockerContainerId() {
		return _liferayDockerServerConfig.dockerContainerId;
	}

	public String getDockerImageId() {
		return _liferayDockerServerConfig.dockerImageId;
	}

	@NotNull
	@Override
	public Map<String, String> getEnvs() {
		return _envs;
	}

	public Module getModule() {
		return _javaRunConfigurationModule.getModule();
	}

	@NotNull
	public Module[] getModules() {
		Module module = _javaRunConfigurationModule.getModule();

		if (module != null) {
			return new Module[] {module};
		}

		return Module.EMPTY_ARRAY;
	}

	@Nullable
	@Override
	public String getProgramParameters() {
		return null;
	}

	@Nullable
	@Override
	public GlobalSearchScope getSearchScope() {
		return ExecutionSearchScopes.executionScope(Arrays.asList(getModules()));
	}

	@Nullable
	@Override
	public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env)
		throws ExecutionException {

		String debugExecutorId = ToolWindowId.DEBUG;

		ExternalSystemRunConfiguration externalSystemRunConfiguration = new ExternalSystemRunConfiguration(
			GradleConstants.SYSTEM_ID, _project, _factory, _name);

		ExternalSystemTaskExecutionSettings settings = externalSystemRunConfiguration.getSettings();

		settings.setExternalProjectPath(_project.getBasePath());
		settings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.toString());
		settings.setScriptParameters(null);

		List<String> taskNames = new ArrayList<>();

		taskNames.add("removeDockerContainer");
		taskNames.add("cleanDockerImage");
		taskNames.add("startDockerContainer");
		taskNames.add("logsDockerContainer");

		List<Module> warCoreExtProjects = getWarCoreExtModules(_project);

		if (ListUtil.isNotEmpty(warCoreExtProjects)) {
			StringBuilder scriptParameters = new StringBuilder();

			for (Module module : warCoreExtProjects) {
				GradleProject gradleProject = GradleUtil.getGradleProject(module);

				if (Objects.nonNull(gradleProject)) {
					scriptParameters.append("-x ");
					scriptParameters.append(gradleProject.getPath());
					scriptParameters.append(":buildExtInfo");
					scriptParameters.append(" -x ");
					scriptParameters.append(gradleProject.getPath());
					scriptParameters.append(":deploy");
					scriptParameters.append(" -x ");
					scriptParameters.append(gradleProject.getPath());
					scriptParameters.append(":dockerDeploy ");
				}
			}

			settings.setScriptParameters(scriptParameters.toString());
		}

		settings.setTaskNames(taskNames);

		ExternalSystemRunnableState runnableState = new ExternalSystemRunnableState(
			settings, getProject(), debugExecutorId.equals(executor.getId()), externalSystemRunConfiguration, env);

		copyUserDataTo(runnableState);

		return runnableState;
	}

	@Nullable
	@Override
	public String getWorkingDirectory() {
		return null;
	}

	@Override
	public boolean isPassParentEnvs() {
		return _liferayDockerServerConfig.passParentEnvironments;
	}

	@Override
	public void onNewConfigurationCreated() {
		super.onNewConfigurationCreated();

		if (StringUtil.isEmpty(getWorkingDirectory())) {
			String baseDir = FileUtil.toSystemIndependentName(StringUtil.notNullize(getProject().getBasePath()));

			setWorkingDirectory(baseDir);
		}
	}

	@Override
	public void readExternal(Element element) throws InvalidDataException {
		super.readExternal(element);

		JavaRunConfigurationExtensionManager javaRunConfigurationExtensionManager =
			JavaRunConfigurationExtensionManager.getInstance();

		javaRunConfigurationExtensionManager.readExternal(this, element);

		XmlSerializer.deserializeInto(_liferayDockerServerConfig, element);
		EnvironmentVariablesComponent.readExternal(element, getEnvs());

		_javaRunConfigurationModule.readExternal(element);
	}

	public void setConfig(LiferayDockerServerConfig config) {
		_liferayDockerServerConfig = config;
	}

	public void setConfigurationModule(JavaRunConfigurationModule configurationModule) {
		_javaRunConfigurationModule = configurationModule;
	}

	public void setDockerContainerId(String dockerContainerId) {
		_liferayDockerServerConfig.dockerContainerId = dockerContainerId;
	}

	public void setDockerImageId(String dockerImageId) {
		_liferayDockerServerConfig.dockerImageId = dockerImageId;
	}

	@Override
	public void setEnvs(@NotNull Map<String, String> envs) {
		_envs.clear();
		_envs.putAll(envs);
	}

	public void setModule(Module module) {
		_javaRunConfigurationModule.setModule(module);
	}

	@Override
	public void setPassParentEnvs(boolean passParentEnvs) {
		_liferayDockerServerConfig.passParentEnvironments = passParentEnvs;
	}

	@Override
	public void setProgramParameters(@Nullable String value) {
	}

	@Override
	public void setWorkingDirectory(@Nullable String value) {
	}

	@Override
	public void writeExternal(Element element) throws WriteExternalException {
		super.writeExternal(element);

		JavaRunConfigurationExtensionManager javaRunConfigurationExtensionManager =
			JavaRunConfigurationExtensionManager.getInstance();

		javaRunConfigurationExtensionManager.writeExternal(this, element);

		XmlSerializer.serializeInto(_liferayDockerServerConfig, element, new SkipEmptySerializationFilter());
		EnvironmentVariablesComponent.writeExternal(element, getEnvs());

		if (_javaRunConfigurationModule.getModule() != null) {
			_javaRunConfigurationModule.writeExternal(element);
		}
	}

	private void _initDockerInfo() {
		Application application = ApplicationManager.getApplication();

		application.invokeLater(
			new Runnable() {

				@Override
				public void run() {
					Computable<ProjectInfo> computable = new Computable<>() {

						@Override
						public ProjectInfo compute() {
							try {
								return GradleUtil.getModel(ProjectInfo.class, ProjectUtil.guessProjectDir(_project));
							}
							catch (Exception exception) {
							}

							return null;
						}

					};

					SwingUtilities.invokeLater(
						() -> {
							try {
								ProjectInfo projectInfo = computable.get();

								if (projectInfo != null) {
									_liferayDockerServerConfig.dockerImageId = projectInfo.getDockerImageId();
									_liferayDockerServerConfig.dockerContainerId = projectInfo.getDockerContainerId();
								}
							}
							catch (Exception exception) {
							}
						});
				}

			});
	}

	private Map<String, String> _envs = new LinkedHashMap<>();
	private ConfigurationFactory _factory;
	private JavaRunConfigurationModule _javaRunConfigurationModule;
	private LiferayDockerServerConfig _liferayDockerServerConfig = new LiferayDockerServerConfig();
	private String _name;
	private Project _project;

	private static class LiferayDockerServerConfig {

		public String dockerContainerId = "";
		public String dockerImageId = "";
		public boolean passParentEnvironments = true;
		public String workspaceLocation = "";

	}

}