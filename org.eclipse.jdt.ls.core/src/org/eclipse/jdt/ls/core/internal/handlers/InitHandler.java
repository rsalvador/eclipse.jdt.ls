/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *     IBM Corporation (Markus Keller)
 *     Microsoft Corporation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.ServiceStatus;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;

/**
 * Handler for the VS Code extension initialization
 */
final public class InitHandler {

	public static final String JAVA_LS_INITIALIZATION_JOBS = "java-ls-initialization-jobs";
	private static final String BUNDLES_KEY = "bundles";
	public static final String SETTINGS_KEY = "settings";

	private ProjectsManager projectsManager;
	private JavaClientConnection connection;
	private PreferenceManager preferenceManager;
	private static WorkspaceDiagnosticsHandler workspaceDiagnosticsHandler;

	public InitHandler(ProjectsManager manager, PreferenceManager preferenceManager, JavaClientConnection connection) {
		this.projectsManager = manager;
		this.connection = connection;
		this.preferenceManager = preferenceManager;
	}

	InitializeResult initialize(InitializeParams param) {
		logInfo("Initializing Java Language Server " + JavaLanguageServerPlugin.getVersion());
		if (param.getCapabilities() == null) {
			preferenceManager.updateClientPrefences(new ClientCapabilities());
		} else {
			preferenceManager.updateClientPrefences(param.getCapabilities());
		}

		Map<?, ?> initializationOptions = this.getInitializationOptions(param);

		Collection<IPath> rootPaths = new ArrayList<>();
		Collection<String> workspaceFolders = getWorkspaceFolders(initializationOptions);
		if (workspaceFolders != null && !workspaceFolders.isEmpty()) {
			for (String uri : workspaceFolders) {
				IPath filePath = ResourceUtils.filePathFromURI(uri);
				if (filePath != null) {
					rootPaths.add(filePath);
				}
			}
			preferenceManager.getClientPreferences().setWorkspaceFoldersSupported(true); // workaround for https://github.com/eclipse/lsp4j/issues/124
		} else {
			String rootPath = param.getRootUri();
			if (rootPath == null) {
				rootPath = param.getRootPath();
				if (rootPath != null) {
					logInfo("In LSP 3.0, InitializeParams.rootPath is deprecated in favour of InitializeParams.rootUri!");
				}
			}
			if (rootPath != null) {
				IPath filePath = ResourceUtils.filePathFromURI(rootPath);
				if (filePath != null) {
					rootPaths.add(filePath);
				}
			}
		}
		if (rootPaths.isEmpty()) {
			IPath workspaceLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
			logInfo("No workspace folders or root uri was defined. Falling back on " + workspaceLocation);
			rootPaths.add(workspaceLocation);
		}
		if (initializationOptions instanceof Map && initializationOptions.get(SETTINGS_KEY) instanceof Map) {
			Object settings = initializationOptions.get(SETTINGS_KEY);
			@SuppressWarnings("unchecked")
			Preferences prefs = Preferences.createFrom((Map<String, Object>) settings);
			preferenceManager.update(prefs);
		}
		triggerInitialization(rootPaths);
		addWorkspaceDiagnosticsHandler();
		Integer processId = param.getProcessId();
		if (processId != null) {
			JavaLanguageServerPlugin.getLanguageServer().setParentProcessId(processId.longValue());
		}
		try {
			Collection<String> bundleList = getBundleList(initializationOptions);
			BundleUtils.loadBundles(bundleList);
		} catch (CoreException e) {
			// The additional plug-ins should not affect the main language server loading.
			JavaLanguageServerPlugin.logException("Failed to load extension bundles ", e);
		}
		InitializeResult result = new InitializeResult();
		ServerCapabilities capabilities = new ServerCapabilities();
		capabilities.setCompletionProvider(new CompletionOptions(Boolean.TRUE, Arrays.asList(".", "@", "#")));
		if (!preferenceManager.getClientPreferences().isFormattingDynamicRegistrationSupported()) {
			capabilities.setDocumentFormattingProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isRangeFormattingDynamicRegistrationSupported()) {
			capabilities.setDocumentRangeFormattingProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isCodeLensDynamicRegistrationSupported()) {
			capabilities.setCodeLensProvider(new CodeLensOptions(true));
		}
		if (!preferenceManager.getClientPreferences().isSignatureHelpDynamicRegistrationSupported()) {
			capabilities.setSignatureHelpProvider(SignatureHelpHandler.createOptions());
		}
		if (!preferenceManager.getClientPreferences().isRenameDynamicRegistrationSupported()) {
			capabilities.setRenameProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isCodeActionDynamicRegistered()) {
			capabilities.setCodeActionProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isExecuteCommandDynamicRegistrationSupported()) {
			Set<String> commands = WorkspaceExecuteCommandHandler.getCommands();
			capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(new ArrayList<>(commands)));
		}
		if (!preferenceManager.getClientPreferences().isWorkspaceSymbolDynamicRegistered()) {
			capabilities.setWorkspaceSymbolProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isDocumentSymbolDynamicRegistered()) {
			capabilities.setDocumentSymbolProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isDefinitionDynamicRegistered()) {
			capabilities.setDefinitionProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isHoverDynamicRegistered()) {
			capabilities.setHoverProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isReferencesDynamicRegistered()) {
			capabilities.setReferencesProvider(Boolean.TRUE);
		}
		if (!preferenceManager.getClientPreferences().isDocumentHighlightDynamicRegistered()) {
			capabilities.setDocumentHighlightProvider(Boolean.TRUE);
		}
		TextDocumentSyncOptions textDocumentSyncOptions = new TextDocumentSyncOptions();
		textDocumentSyncOptions.setOpenClose(Boolean.TRUE);
		textDocumentSyncOptions.setSave(new SaveOptions(Boolean.TRUE));
		textDocumentSyncOptions.setChange(TextDocumentSyncKind.Incremental);
		if (preferenceManager.getClientPreferences().isWillSaveRegistered()) {
			textDocumentSyncOptions.setWillSave(Boolean.TRUE);
		}

		if (preferenceManager.getClientPreferences().isWillSaveWaitUntilRegistered()) {
			textDocumentSyncOptions.setWillSaveWaitUntil(Boolean.TRUE);
		}
		capabilities.setTextDocumentSync(textDocumentSyncOptions);

		result.setCapabilities(capabilities);
		return result;
	}

	private void triggerInitialization(Collection<IPath> roots) {
		Job job = new WorkspaceJob("Initialize Workspace") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) {
				long start = System.currentTimeMillis();
				connection.sendStatus(ServiceStatus.Starting, "Init...");
				SubMonitor subMonitor = SubMonitor.convert(new ServerStatusMonitor(), 100);
				try {
					projectsManager.setAutoBuilding(false);
					projectsManager.initializeProjects(roots, subMonitor);
					JavaLanguageServerPlugin.logInfo("Workspace initialized in " + (System.currentTimeMillis() - start) + "ms");
					connection.sendStatus(ServiceStatus.Started, "Ready");
				} catch (OperationCanceledException e) {
					connection.sendStatus(ServiceStatus.Error, "Initialization has been cancelled.");
				} catch (Exception e) {
					JavaLanguageServerPlugin.logException("Initialization failed ", e);
					connection.sendStatus(ServiceStatus.Error, e.getMessage());
				}
				return Status.OK_STATUS;
			}

			/* (non-Javadoc)
			 * @see org.eclipse.core.runtime.jobs.Job#belongsTo(java.lang.Object)
			 */
			@Override
			public boolean belongsTo(Object family) {
				return JAVA_LS_INITIALIZATION_JOBS.equals(family);
			}

		};
		job.setPriority(Job.BUILD);
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.schedule();
	}

	private Map<?, ?> getInitializationOptions(InitializeParams params) {
		Object initializationOptions = params.getInitializationOptions();
		if (initializationOptions instanceof Map<?, ?>) {
			return (Map<?, ?>) initializationOptions;
		}
		return null;
	}

	private Collection<String> getWorkspaceFolders(Map<?, ?> initializationOptions) {
		if (initializationOptions != null) {
			Object folders = initializationOptions.get("workspaceFolders");
			if (folders instanceof Collection<?>) {
				return (Collection<String>) folders;
			}
		}
		return null;
	}
	private Collection<String> getBundleList(Map<?, ?> initializationOptions) {
		if (initializationOptions != null) {
			Object bundleObject = initializationOptions.get(BUNDLES_KEY);
			if (bundleObject instanceof Collection<?>) {
				return (Collection<String>) bundleObject;
			}
		}
		return null;
	}

	public static void removeWorkspaceDiagnosticsHandler() {
		if (workspaceDiagnosticsHandler != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(workspaceDiagnosticsHandler);
			workspaceDiagnosticsHandler = null;
		}
	}

	public void addWorkspaceDiagnosticsHandler() {
		removeWorkspaceDiagnosticsHandler();
		workspaceDiagnosticsHandler = new WorkspaceDiagnosticsHandler(connection, projectsManager);
		ResourcesPlugin.getWorkspace().addResourceChangeListener(workspaceDiagnosticsHandler, IResourceChangeEvent.POST_CHANGE);
	}

	private class ServerStatusMonitor extends NullProgressMonitor {
		private final static long DELAY = 200;

		private double totalWork;
		private String subtask;
		private double progress;
		private long lastReport = 0;

		@Override
		public void beginTask(String task, int totalWork) {
			this.totalWork = totalWork;
			sendProgress();
		}

		@Override
		public void subTask(String name) {
			this.subtask = name;
			sendProgress();
		}

		@Override
		public void worked(int work) {
			progress += work;
			sendProgress();
		}

		private void sendProgress() {
			// throttle the sending of progress
			long currentTime = System.currentTimeMillis();
			if (lastReport == 0 || currentTime - lastReport > DELAY) {
				lastReport = currentTime;
				String message = this.subtask == null || this.subtask.length() == 0 ? "" : (" - " + this.subtask);
				connection.sendStatus(ServiceStatus.Starting, String.format("%.0f%%  Starting Java Language Server %s", progress / totalWork * 100, message));
			}
		}

	}
}
