package ocaml.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ocaml.OcamlPlugin;
import ocaml.build.OcamlBuilder;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

/**
 * Manages a project paths:
 * <ul>
 * <li> passed as a parameter to the compiler with the -I option
 * <li> used for completion
 * </ul>
 */
public class OcamlPaths {

	/** the name of the external sources folder (.ml) for the debugger */
	public static final String EXTERNAL_SOURCES = ".DebuggerSourceLookup";

	public static final String PATHS_FILE = ".paths";

	private final IProject project;

	public OcamlPaths(IProject project) {
		this.project = project;
	}

	public void setPaths(String[] paths) {
		IPath pathsPath = project.getLocation().append(PATHS_FILE);

		File file = pathsPath.toFile();
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter(file));

			for (String path : paths)
				if (path != null)
					writer.write(path + "\n");

			writer.close();
		} catch (IOException e) {
			OcamlPlugin.logError("ocaml plugin error", e);
			return;
		}

		// create the external sources folder
		IFolder sourcesFolder = project.getFolder(EXTERNAL_SOURCES);
		if (!sourcesFolder.exists()) {
			try {
				sourcesFolder.create(IFolder.FORCE, true, null);
				Misc.setFolderProperty(sourcesFolder, Misc.EXTERNAL_SOURCES_FOLDER, "true");

				/*
				 * ResourceAttributes attributes = sourcesFolder.getResourceAttributes(); if (attributes !=
				 * null) { attributes.setReadOnly(true); sourcesFolder.setResourceAttributes(attributes); }
				 * else OcamlPlugin.logError("cannot set '" + EXTERNAL_SOURCES + "' as read only");
				 */

			} catch (Exception e) {
				OcamlPlugin.logError("ocaml plugin error", e);
				return;
			}
		}

		// check that the folder has the right property
		if (!Misc.getResourceProperty(sourcesFolder, Misc.EXTERNAL_SOURCES_FOLDER).equals("true")) {
			Misc.setFolderProperty(sourcesFolder, Misc.EXTERNAL_SOURCES_FOLDER, "true");
		}

		// link the external paths to the project
		/*
		 * Go through the list in reverse, so that files in the first paths will override files in the
		 * following paths (in case they have the same name)
		 */
		for (int i = paths.length - 1; i >= 0; i--) {
			String path = paths[i];

			if (path != null && !isRelativePath(project, path)) {
				File dir = new File(path);
				File[] mlFiles = dir.listFiles(new FilenameFilter() {
					public boolean accept(File dir, String name) {
						return name.endsWith(".ml");
					}
				});

				if (mlFiles != null) {
					for (File mlFile : mlFiles) {
						IPath location = new Path(mlFile.getAbsolutePath());
						if (project.getWorkspace().validateLinkLocation(sourcesFolder, location).isOK()) {
							try {
								IFile linkedFile = sourcesFolder.getFile(mlFile.getName());
								linkedFile.createLink(location, IResource.REPLACE, null);
							} catch (CoreException e) {
								OcamlPlugin.logError("ocaml plugin error", e);
							}
						}

					}
				}
			}
		}
	}

	public void restoreDefaults() {
		ArrayList<String> paths = new ArrayList<String>();
		paths.add(".");

		String projectName = this.project.getName();

		IFolder[] folders = Misc.getProjectFolders(this.project);
		mainLoop: for (IFolder folder : folders) {
			final String folderName = folder.getFullPath().lastSegment();
			if (!folder.isLinked() && !folderName.equals(".settings")
					&& Misc.getFolderProperty(folder, Misc.EXTERNAL_SOURCES_FOLDER).equals("")
					&& !folderName.equals(OcamlBuilder.EXTERNALFILES)
					&& !folderName.equals(Misc.HYPERLINKSDIR)) {
				IPath path = folder.getFullPath();

				for (String segment : path.segments()) {
					if ("_build".equals(segment))
						continue mainLoop;
				}

				// if the first segment is the project name, we remove it
				if (path.segmentCount() > 0 && path.segment(0).equals(projectName))
					paths.add(path.removeFirstSegments(1).toPortableString());
				else
					paths.add(path.toPortableString());
			}
		}

		paths.add(OcamlPlugin.getLibFullPath());
		this.setPaths(paths.toArray(new String[paths.size()]));
	}

	public String[] getPaths() {
		ArrayList<String> paths = new ArrayList<String>();

		IPath pathsPath = project.getLocation().append(PATHS_FILE);

		File file = pathsPath.toFile();

		if (!file.exists())
			restoreDefaults();

		try {
			BufferedReader reader = new BufferedReader(new FileReader(file));

			String path = "";
			while ((path = reader.readLine()) != null)
				paths.add(path);

			reader.close();

		} catch (IOException e) {
			OcamlPlugin.logError("ocaml plugin error", e);
			return new String[0];
		}

		addReferencedProjectsPaths(paths);

		return paths.toArray(new String[paths.size()]);
	}

	private void addReferencedProjectsPaths(ArrayList<String> paths) {
		try {
			IProject[] referencedProjects = project.getReferencedProjects();

			for (IProject referencedProject : referencedProjects) {
				OcamlPaths opaths = new OcamlPaths(referencedProject);

				for (String p : opaths.getPaths()) {
					File f = new File(p);
					if (f.isAbsolute())
						paths.add(p);
					else {
						File absolutePath = new File(referencedProject.getLocation().toOSString(), p);
						paths.add(absolutePath.getPath());
					}

					// if(".".equals(p))
					// paths.add(referencedProject.getLocation().toOSString());
				}
			}

		} catch (CoreException e) {
			OcamlPlugin.logError("Error while trying to get referenced projects paths for project "
					+ project.getName(), e);
		}

	}

	/** @return true if the path is valid in this project */
	public static boolean isValidPath(IProject project, String strPath) {
		if (strPath == null || strPath.equals(""))
			return false;

		if (isRelativePath(project, strPath))
			return true;

		File file = new File(strPath);
		return file.exists() && file.isDirectory();
	}

	/** @return true if this path is relative to the project */
	public static boolean isRelativePath(IProject project, String strPath) {
		IPath path = Path.fromOSString(strPath);
		path = path.makeRelative();

		IResource resource = project.findMember(path);

		if (resource != null) {
			if (resource instanceof IFolder) {
				IFolder folder = (IFolder) resource;
				if (folder.exists())
					return true;
			}
			if (resource instanceof IProject) {
				IProject proj = (IProject) resource;
				if (proj.exists())
					return true;
			}

		}

		return false;
	}

	/**
	 * Add a path list to a project
	 * <p>
	 * Note: If a path does not start by {@link File#separatorChar} it will be considered as relative to the
	 * project.
	 * 
	 * @param paths
	 *            the list of paths to add
	 * @param project
	 *            the project to add them to
	 */
	public static void addToPaths(final List<IPath> paths, final IProject project) {
		final OcamlPaths currentOcamlPaths = new OcamlPaths(project);
		final String[] currentPaths = currentOcamlPaths.getPaths();

		final String[] pathsToAdd = new String[currentPaths.length + paths.size()];
		int i = 0;

		for (IPath path : paths) {
			if (!path.isAbsolute()) {
				final IResource res = project.findMember(path);
				if ((res == null) || (res.getType() != IResource.FOLDER)
						&& (res.getType() != IResource.PROJECT)) {
					OcamlPlugin
							.logError("error in " + "OcamlPaths:addToPath: path not found or not a folder");
				} else {
					final String name = res.getName();
					if (!res.isLinked() && !name.equals(".settings")
							&& Misc.getResourceProperty(res, Misc.EXTERNAL_SOURCES_FOLDER).equals("")
							&& !name.equals(OcamlBuilder.EXTERNALFILES) && !name.equals(Misc.HYPERLINKSDIR)) {
						boolean found = false;
						final String strPath = path.isEmpty() ? "." : path.toOSString();
						for (int j = 0; j < pathsToAdd.length && !found; j++) {
							if (pathsToAdd[j] != null && pathsToAdd[j].equals(strPath))
								found = true;
						}
						if (!found)
							pathsToAdd[i++] = strPath;
					}
				}
			}
			// the path is absolute
			else {
				boolean found = false;
				for (int j = 0; j < pathsToAdd.length && !found; j++) {
					if (pathsToAdd[j] != null && pathsToAdd[j].equals(path.toOSString()))
						found = true;
				}
				if (!found)
					pathsToAdd[i++] = path.toOSString();
			}

		}

		for (String curPath : currentPaths) {
			boolean found = false;
			for (int j = 0; j < pathsToAdd.length && !found; j++) {
				if (pathsToAdd[j] != null && pathsToAdd[j].equals(curPath))
					found = true;
			}
			if (!found)
				pathsToAdd[i++] = curPath;
		}

		currentOcamlPaths.setPaths(pathsToAdd);
	}
}
