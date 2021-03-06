/*
 *  Copyright (C) 2012-2013 CloudJee, Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.studio.phonegap;

import java.io.StringReader;
import java.io.StringWriter;

import org.springframework.util.Assert;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import com.wavemaker.common.WMRuntimeException;
import com.wavemaker.common.util.SystemUtils;
import com.wavemaker.runtime.server.Downloadable;
import com.wavemaker.runtime.service.annotations.ExposeToClient;
import com.wavemaker.runtime.service.annotations.HideFromClient;
import com.wavemaker.tools.io.File;
import com.wavemaker.tools.io.FilterOn;
import com.wavemaker.tools.io.FilterOn.AttributeFilter;
import com.wavemaker.tools.io.Folder;
import com.wavemaker.tools.io.Resource;
import com.wavemaker.tools.io.Resources;
import com.wavemaker.tools.project.DownloadableFolder;
import com.wavemaker.tools.project.Project;
import com.wavemaker.tools.project.ProjectManager;
import com.wavemaker.tools.project.StudioFileSystem;

/**
 * Service for Phone Gap operations.
 *
 * @author Michael Kantor
 */
@HideFromClient
public class PhoneGapService {

	private static enum FolderLayout {
		XCODE, ECLIPSE, PHONEGAP_BUILD_SERVICE
	}

	private ProjectManager projectManager;

	private StudioFileSystem fileSystem;

	/**
	 * Returns the default host to use when {@link #generateBuild(String, int, String) generating} phone gap builds.
	 *
	 * @return the default host
	 */
	@ExposeToClient
	public String getDefaultHost() {
		return SystemUtils.getIP();
	}

	/**
	 * Generate a PhoneGap folder structure compatible with the PhoneGap build service.
	 *
	 * @param serverName the name of the server
	 * @param portNumb the port number of the service
	 * @param themeName the theme name
	 */
	@ExposeToClient

	    public void generateBuild(String xhrPath, String themePath, String tabletThemePath, String phoneThemePath, String configxml, boolean useProxy) {
		try{
			Project currentProject = this.projectManager.getCurrentProject();
			currentProject.getRootFolder().getFolder("phonegap").createIfMissing();
			getPhoneGapFolder(FolderLayout.PHONEGAP_BUILD_SERVICE).createIfMissing();
			setupPhonegapFiles(FolderLayout.PHONEGAP_BUILD_SERVICE);
			updatePhonegapFiles(xhrPath, FolderLayout.PHONEGAP_BUILD_SERVICE, themePath, tabletThemePath, phoneThemePath, useProxy);

			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();

			StreamSource source = new StreamSource(new StringReader(configxml));
			StringWriter writer = new StringWriter();
			StreamResult result = new StreamResult(writer);
			transformer.transform(source, result);
			getPhoneGapFolder(FolderLayout.PHONEGAP_BUILD_SERVICE).getFile("config.xml").getContent().write(writer.toString());
		}
		catch (TransformerException tfe){
			throw new WMRuntimeException("Invalid character in Phonegap Build Config field data. Remove and retry. " + tfe.getMessage());
		}
		catch (Exception e){
			throw new WMRuntimeException(e);
		}
	}

	/**
	 * Download a previously {@link #generateBuild(String, int, String) generated} PhoneGap build folder.
	 *
	 * @return {@link Downloadable} zip file
	 */
	@ExposeToClient
	public Downloadable downloadBuild() {
		Folder phoneGapFolder = getPhoneGapFolder(FolderLayout.PHONEGAP_BUILD_SERVICE);
		return new DownloadableFolder(phoneGapFolder, this.projectManager.getCurrentProject().getProjectName());
	}

	/**
	 * Setup the an initial phone gap project structure.
	 */
	@ExposeToClient
	public void setupPhonegapFiles() {
		for (FolderLayout layout : FolderLayout.values()) {
			if (layout != FolderLayout.PHONEGAP_BUILD_SERVICE) {
				setupPhonegapFiles(layout);
			}
		}
	}

	/**
	 * Update an existing phone gap project structure.
	 */
	@ExposeToClient
	    public void updatePhonegapFiles(int portNumb, String themePath, String tabletThemePath, String phoneThemePath) {
		setupPhonegapFiles();
		String serverUrl = SystemUtils.getIP();
		String projectName = this.projectManager.getCurrentProject().getProjectName();
		for (FolderLayout layout : FolderLayout.values()) {
			if (layout != FolderLayout.PHONEGAP_BUILD_SERVICE) {
			    updatePhonegapFiles("http://" + serverUrl + ":" + portNumb + "/" + projectName, layout, themePath, tabletThemePath, phoneThemePath, false);
			}
		}
		fixupXCodeFilesFollowingUpdate();
	}

	private void setupPhonegapFiles(FolderLayout layout) {
		Folder phoneGapWebFolder = getPhoneGapFolder(layout);
		if (!phoneGapWebFolder.exists()) {
			return;
		}
		Folder phoneGapLibFolder = phoneGapWebFolder.getFolder("lib");
		if (isOutOfDate(phoneGapLibFolder)) {
			phoneGapLibFolder.delete();
		}
		if (phoneGapLibFolder.exists()) {
			return;
		}
		setupPhonegapProjectFiles(phoneGapWebFolder, phoneGapLibFolder);
		purgeUnnecessarySetupFiles(phoneGapLibFolder);
	}

	private boolean isOutOfDate(Folder phoneGapLibFolder) {
		File phoneGapVersion = phoneGapLibFolder.getFile("wm/WMVersion.txt");
		File wavemakerVersion = this.fileSystem.getStudioWebAppRootFolder().getFile("lib/wm/WMVersion.txt");
		if (!phoneGapVersion.exists()) {
			return true;
		}
		return !phoneGapVersion.getContent().asString().equals(wavemakerVersion.getContent().asString());
	}

	private void setupPhonegapProjectFiles(final Folder phoneGapWebFolder, Folder phoneGapLibFolder) {
		this.fileSystem.getStudioWebAppRootFolder().getFolder("lib").copyContentsTo(phoneGapLibFolder);
		this.projectManager.getCurrentProject().getRootFolder().getFolder("webapproot/pages").copyContentsTo(phoneGapWebFolder.getFolder("pages"));

		Folder sourceFolder = this.projectManager.getCurrentProject().getWebAppRootFolder();
		for (String filename : new String[] { "config.js", "boot.js" }) {
			File sourceFile = sourceFolder.getFile(filename);
			File destinationFile = phoneGapWebFolder.getFile(filename);
			if (sourceFile.exists() && !destinationFile.exists()) {
				destinationFile.getContent().write(sourceFile);
			}
		}
	}

	private void purgeUnnecessarySetupFiles(Folder phoneGapLibFolder) {
		phoneGapLibFolder.getFolder("build/Gzipped").list().files().include(FilterOn.names().ending(".gz")).delete();
		phoneGapLibFolder.getFolder("build/Gzipped").list().files().include(FilterOn.names().ending("_grid.js")).delete();
		phoneGapLibFolder.getFolder("build/Gzipped").list().files().include(FilterOn.names().starting("lib_build_mobile")).delete(); // use lib_build_phonegap instead
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.names().starting("wm_data_grid")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.names().starting("wm_colorpicker")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.names().starting("wm_dashboard")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.names().starting("wm_dojo_grid")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.names().starting("wm_editors_old")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.names().starting("lib_build_mobile")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().files().include(FilterOn.antPattern("lib_build*")).exclude(FilterOn.names().starting("lib_build_phonegap")).delete();
		phoneGapLibFolder.getFolder("build/nls").list().folders().delete();
		phoneGapLibFolder.getFolder("build/themes").list().include(FilterOn.names().notEnding(".css").notMatching("tundra")).delete();
		phoneGapLibFolder.getFolder("build").list().files().include(FilterOn.names().ending(".js")).delete();

		phoneGapLibFolder.getFolder("images/boolean/").delete();
		phoneGapLibFolder.getFile("github/beautify.js").delete();
		Folder dojo = phoneGapLibFolder.getFolder("dojo");
		dojo.getFolder("util").delete();
		dojo.getFolder("dojox").list().include(FilterOn.names().notMatching("charting")).delete();

		dojo.getFolder("dojox/charting/tests").delete();
		dojo.getFolder("dojo").list().files().include(FilterOn.names().notMatching("dojo_build.js")).delete();
		dojo.getFolder("dojo/_base").delete();
		dojo.getFolder("dojo/_firebug").delete();
		dojo.getFolder("dojo/cldr").delete();
		dojo.getFolder("dojo/date").delete();
		dojo.getFolder("dojo/dnd").delete();
		dojo.getFolder("dojo/fx").delete();
		dojo.getFolder("dojo/io").delete();
		dojo.getFolder("dojo/lib").delete();
		dojo.getFolder("dojo/nls").delete();
		dojo.getFolder("dojo/resources").delete();
		dojo.getFolder("dojo/rpc").delete();
		dojo.getFolder("dojo/store").delete();
		dojo.getFolder("dojo/tests").delete();
		dojo.getFolder("dijit").list().include(FilterOn.names().notMatching("themes")).delete();
		dojo.getFolder("dijit/themes").list().include(FilterOn.names().notEnding(".css").notMatching("tundra")).delete();
		Folder wm = phoneGapLibFolder.getFolder("wm");
		wm.getFolder("compressed").delete();
		wm.getFolder("etc").delete();
		Folder base = wm.getFolder("base");
		base.list().include(FilterOn.names().matching("deprecated", "components", "design", "drag", "styles", "templates", "debug")).delete();
		base.list().include(FilterOn.names().ending(".js")).delete();
		Folder widget = base.getFolder("widget");
		widget.getFolder("Editors").delete(); // all editors are in a build layer
		widget.getFolder("themes/default/images/omg").delete();

		widget.list().files().include(FilterOn.names().ending("_design.js")).delete();
		String[] purgedWidgetResources = { "Buttons/Button_design.js", "Trees/Tree_design.js", "Dialogs/Dialog_design.js", "AccordionLayers.js",
				"DojoMenu.js", "AppRoot.js", "PageContainer.js", "Bevel.js", "EditPanel.js", "Panel.js", "BreadcrumbLayers.js", "Button.js", "Editor.js",
				"Picture.js", "Container.js", "FileUpload.js", "Formatters.js", "Scrim.js", "Select.js", "Html.js", "Spacer.js", "ContextMenuDialog.js",
				"Html.js", "Splitter.js", "Input.js", "DataForm.js", "DataGrid.js", "Label.js", "Layers.js", "DojoChart.js", "Tree.js", "LayoutBox.js",
				"List.js", "DojoGrid.js", "LiveForm.js", "Dialogs", "List" };
		for (String purgedResource : purgedWidgetResources) {
			if (widget.hasExisting(purgedResource)) {
				widget.getExisting(purgedResource).delete();
			}
		}

		Folder themes = widget.getFolder("themes");
		themes.list().include(FilterOn.names().notMatching("default")).delete();
	}

    private void updatePhonegapFiles(String url, FolderLayout layout, String themePath, String tabletThemePath, String phoneThemePath, boolean useProxy) {
		Folder phoneGapFolder = getPhoneGapFolder(layout);
		if (!phoneGapFolder.exists()) {
			return;
		}

		Folder projectFolder = this.projectManager.getCurrentProject().getRootFolder();

		// Delete all pages, resources and project files so we can re-copy updated version of them
		AttributeFilter skippedResources = FilterOn.names().notMatching("config.js", "lib");
		phoneGapFolder.list().include(skippedResources.notStarting("cordova")).delete();

		// Copy project files for phonegap
		projectFolder.getFolder("webapproot").list().include(skippedResources.notMatching("WEB-INF")).copyTo(phoneGapFolder);

		// Update index and login HTML files
		String phonegapName = getPhoneGapScript(phoneGapFolder);
		updateHtmlFile(phonegapName, phoneGapFolder.getFile("index.html"), themePath, tabletThemePath, phoneThemePath);
		updateHtmlFile(phonegapName, phoneGapFolder.getFile("login.html"), themePath, tabletThemePath, phoneThemePath);

		// Combine boot.js and config.js
		phoneGapFolder.getFile("config.js").getContent().write(combineBootAndConfig(url, useProxy));

		Folder commonFolder = phoneGapFolder.getFolder("common");
		commonFolder.createIfMissing();
		this.fileSystem.getCommonFolder().copyContentsTo(commonFolder);

		Folder themesFolder = phoneGapFolder.getFolder("themes");
		themesFolder.createIfMissing();

		// Copy theme
		if (themePath.startsWith("wm.base.widget.themes") || themePath.startsWith("common.themes")) {
		    Folder theme = getThemeFolder(themePath);
		    theme.copyTo(themesFolder);
		}
		if (!tabletThemePath.equals("") && !tabletThemePath.equals(themePath)) {
		    Folder theme = getThemeFolder(tabletThemePath);
		    theme.copyTo(themesFolder);
		}
		if (!phoneThemePath.equals("") && !phoneThemePath.equals(themePath) && !phoneThemePath.equals(tabletThemePath)) {
		    Folder theme = getThemeFolder(phoneThemePath);
		    theme.copyTo(themesFolder);
		}
	}

	private String getPhoneGapScript(Folder phoneGapFolder) {
		Resources<Resource> files = phoneGapFolder.list().include(FilterOn.names().starting("cordova-").ending(".js"));
		for (Resource resource : files) {
			return resource.getName();
		}
		return "cordova.js";
	}

        private void updateHtmlFile(String phoneGapScript, File file, String themePath, String tabletThemePath, String phoneThemePath) {
		if (!file.exists()) {
			return;
		}
		String phoneGapScriptTag = "<script type=\"text/javascript\" src=\"" + phoneGapScript + "\"></script>";
		String insertLocation = "runtimeLoader.js\"></script>";
		String content = file.getContent().asString();
		if (!content.contains(phoneGapScriptTag)) {
			content = content.replace(insertLocation, insertLocation + "\n" + phoneGapScriptTag);
		}
		content = content.replaceAll("/wavemaker/", "");
		content = fixThemePath(content, themePath, "wmThemeUrl");
		content = fixThemePath(content, tabletThemePath, "wmThemeTabletUrl");
		content = fixThemePath(content, phoneThemePath, "wmThemePhoneUrl");

		file.getContent().write(content);
	}

        private String fixThemePath(String content, String themePath, String varName) {
	        /* If its not one of these, then we don't really know what it is and can't work with it */
	        if (themePath.equals("")) return content;
		if (themePath.startsWith("wm.base.widget") || themePath.startsWith("common.themes")) {
		    String themeUrlVar = "var " + varName + " =";
		    if (content.contains(themeUrlVar)) {
			int start = content.indexOf(themeUrlVar);
			int end = content.indexOf(";", start);
			if (end != -1) {
			    String themeName = content.substring(start,end); // now string is "wmThemeUrl = aa/bb/cc/dd/theme.css" or "wmThemeUrl = "resources/mytheme/theme.css"
			    themeName = themeName.substring(0, themeName.lastIndexOf("/")); // now string is "wmThemeUrl = aa/bb/cc/dd" or "wmThemeUrl = "resources/mytheme"
			    themeName = themeName.substring(themeName.lastIndexOf("/") + 1); // now string is "dd" or "mytheme"
			    if (!themeName.equals("") && themeName.indexOf("/") == -1) {
				content = content.substring(0, start) + themeUrlVar + " \"themes/" + themeName + "/theme.css\"" + content.substring(end);
			    }
			}
		    }
		}
		return content;
	}

	private String combineBootAndConfig(String url, boolean useProxy) {
		Folder projectRoot = this.projectManager.getCurrentProject().getRootFolder();
		String config = projectRoot.getFile("webapproot/config.js").getContent().asString();
		String boot = projectRoot.getFile("webapproot/boot.js").getContent().asString();
		config = config.replaceAll("/wavemaker/", "/");
		config = config.replace("wm.relativeLibPath = \"../lib/\";", "wm.relativeLibPath = \"lib/\";");
		config = config + "\nwm.xhrPath = '" + url + "/';";
		config = config + "\nwm.useProxyJsonServices = " + useProxy + ";";
		config = config + "\nwm.isPhonegap = true;\n";
		return config + boot;
	}

	private Folder getThemeFolder(String themePath) {
		Folder themeFolder;
		String themeName = themePath.substring(themePath.lastIndexOf(".")+1);
		if (themePath.startsWith("wm.base.widget")) {
			themeFolder = this.fileSystem.getStudioWebAppRootFolder().getFolder("lib/wm/base/widget/themes/" + themeName);
		} else {
			themeFolder = this.fileSystem.getCommonFolder().getFolder("themes/" + themeName);
		}
		Assert.isTrue(themeFolder.exists(), "Unable to find theme folder for theme " + themeName);
		return themeFolder;
	}

	private void fixupXCodeFilesFollowingUpdate() {
		String projectName = this.projectManager.getCurrentProject().getProjectName();
		Folder xcodePhoneGapFolder = getPhoneGapFolder(FolderLayout.XCODE);
		File file = xcodePhoneGapFolder.getFile("../" + projectName + "/Cordova.plist");
		if (file.exists()) {
			String content = file.getContent().asString();
			String startExpression = "<key>ExternalHosts</key>";
			int startindex = content.indexOf(startExpression);
			int startindex1 = startindex + startExpression.length();
			int endindex1 = content.indexOf("</array>", startindex1);
			if (endindex1 != -1) {
				endindex1 += "</array>".length();
			}
			int endindex2 = content.indexOf("<array/>", startindex1);
			if (endindex2 != -1) {
				endindex2 += "<array/>".length();
			}
			int endindex;
			if (endindex1 == -1) {
				endindex = endindex2;
			} else if (endindex2 == -1) {
				endindex = endindex1;
			} else if (endindex1 > endindex2) {
				endindex = endindex2;
			} else {
				endindex = endindex1;
			}
			content = content.substring(0, startindex1) + "<array><string>*</string></array>" + content.substring(endindex);
			file.getContent().write(content);
		}
	}

	private Folder getPhoneGapFolder(FolderLayout layout) {
		Project currentProject = this.projectManager.getCurrentProject();
		switch (layout) {
		case XCODE:
			return currentProject.getRootFolder().getFolder("phonegap/" + currentProject.getProjectName() + "/www");
		case ECLIPSE:
			return currentProject.getRootFolder().getFolder("phonegap/android/assets/www");
		case PHONEGAP_BUILD_SERVICE:
			return currentProject.getRootFolder().getFolder("phonegap/" + currentProject.getProjectName() + "_phonegap_build");
		}
		throw new IllegalStateException("Uknown phonegap layout " + layout);
	}

	public void setProjectManager(ProjectManager projectManager) {
		this.projectManager = projectManager;
	}

	public void setFileSystem(StudioFileSystem fileSystem) {
		this.fileSystem = fileSystem;
	}
}
