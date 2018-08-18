package org.nasdanika.docgen.ecore;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.codegen.ecore.genmodel.GenClassifier;
import org.eclipse.emf.codegen.ecore.genmodel.GenPackage;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.ETypedElement;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nasdanika.doc.ecore.EClassDocumentationGenerator;
import org.nasdanika.doc.ecore.EDataTypeDocumentationGenerator;
import org.nasdanika.doc.ecore.EEnumDocumentationGenerator;
import org.nasdanika.doc.ecore.EPackageDocumentationGenerator;
import org.nasdanika.doc.ecore.PlantUmlTextGenerator.RelationshipDirection;
import org.nasdanika.help.markdown.ExtensibleMarkdownLinkRenderer;
import org.nasdanika.help.markdown.MarkdownLinkRenderer;
import org.nasdanika.help.markdown.MarkdownPreProcessor;
import org.nasdanika.help.markdown.URLRewriter;
import org.nasdanika.html.ApplicationPanel;
import org.nasdanika.html.Bootstrap.Style;
import org.nasdanika.html.HTMLFactory;
import org.nasdanika.html.RowContainer.Row;
import org.nasdanika.html.Table;
import org.nasdanika.html.Tag;
import org.nasdanika.html.Tag.TagName;
import org.nasdanika.html.Theme;
import org.osgi.framework.Bundle;
import org.pegdown.Extensions;
import org.pegdown.PegDownProcessor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.Yaml;

public class GenerateEcoreDocumentationAction implements IObjectActionDelegate {

	private static final String HELP_ICONS_BASE_LOCATION = "../resources/images/";
	private static final String SITE_ICONS_BASE_LOCATION = "resources/images/";
	private static final String ENCODED_PACKAGE_NS_URI_TOKEN = "encoded-epackage-ns-uri";
	
	private List<IFile> selectedFiles = new ArrayList<>();
	
	@SuppressWarnings("serial")
	private class GenerationException extends RuntimeException {

		GenerationException(String message) {
			super(message);
		}
		
	}

	@Override
	public void run(IAction action) {
		if (!selectedFiles.isEmpty()) {
			IWorkbench workbench = PlatformUI.getWorkbench();
			Shell shell = workbench.getModalDialogShellProvider().getShell();
						
			try {							
				WorkspaceModifyOperation operation = new WorkspaceModifyOperation() {
					
					@Override
					protected void execute(IProgressMonitor monitor) throws CoreException, InvocationTargetException, InterruptedException {	
						try {
							SubMonitor subMonitor = SubMonitor.convert(monitor, selectedFiles.size());
							for (IFile selectedFile: selectedFiles) {
								generate(selectedFile, subMonitor.split(1));
							}
						} catch (CoreException | InvocationTargetException | InterruptedException | RuntimeException e) {
							throw e;
						} catch (Exception e) {
							throw new InvocationTargetException(e);
						} finally {
							monitor.done();
						}					
					}
					
				};
	
				new ProgressMonitorDialog(shell).run(true, true, operation);
			} catch (Exception exception) {
				if (exception instanceof InvocationTargetException && exception.getCause() instanceof GenerationException) {
		            ErrorDialog.openError(shell, "Generation error", null, new Status(IStatus.ERROR, Activator.PLUGIN_ID, exception.getCause().getMessage()));					
				} else {
		            MultiStatus status = createMultiStatus(exception.toString(), exception);
		            ErrorDialog.openError(shell, "Generation error", exception.toString(), status);
		            Activator.getDefault().getLog().log(status);
					exception.printStackTrace();
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void generate(IFile specFile, IProgressMonitor monitor) throws Exception {
		Yaml yaml = new Yaml();
		Object spec = yaml.load(specFile.getContents());
		if (!(spec instanceof Map)) {
            throw new GenerationException("Invalid specification");
		}
		Map<String, Object> specMap = (Map<String, Object>) spec;
		Object models = specMap.get("models");
		if (!(models instanceof Iterable)) {
			throw new GenerationException("'models' should be a list");
		}
		
		// References to external locations of documentation.
		Map<String, String> siteModeldocs = Collections.emptyMap();
		Map<String, String> helpModeldocs = Collections.emptyMap();
		
		Object modeldocs = specMap.get("modeldocs");
		if (modeldocs instanceof Map) {
			Object smd = ((Map<?,?>) modeldocs).get("site");
			if (smd instanceof Map) {
				siteModeldocs = (Map<String, String>) smd;
			}
			
			Object hmd = ((Map<?,?>) modeldocs).get("help");
			if (hmd instanceof Map) {
				helpModeldocs = (Map<String, String>) hmd;
			}
		}
		
		Object output = specMap.get("output");
		if (output == null) {
			throw new GenerationException("output is not specified");
		}
		
		boolean setDerived = Boolean.TRUE.equals(specMap.get("set-derived"));
		
		if (!(output instanceof Map)) {
			throw new GenerationException("Invalid output specification");
		}
		
		String specPath = "/"+specFile.getProject().getName()+"/"+ specFile.getProjectRelativePath().toString();
		
		IFolder siteOutput = null;
		String siteOutputStr = (String) ((Map<String, Object>) output).get("site");
		if (siteOutputStr != null) {
			IPath siteLocation = new Path(siteOutputStr);			
			siteOutput = ResourcesPlugin.getWorkspace().getRoot().getFolder(siteLocation);
			if (siteOutput == null) {
				throw new GenerationException("Site output folder does not exist");
			}			
		}
		
		IFolder helpOutput = null;
		String helpOutputStr = (String) ((Map<String, Object>) output).get("help");
		if (helpOutputStr != null) {
			IPath helpLocation = new Path(helpOutputStr);			
			helpOutput = ResourcesPlugin.getWorkspace().getRoot().getFolder(helpLocation);
			if (helpOutput == null) {
				throw new GenerationException("Help output folder does not exist");
			}
		}
		
		ResourceSetImpl resourceSet = new ResourceSetImpl();
		resourceSet.setURIResourceMap(new HashMap<>());
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put(Resource.Factory.Registry.DEFAULT_EXTENSION, new XMIResourceFactoryImpl());

		URI base = URI.createPlatformResourceURI(specPath, true);
		
		Set<EPackage> eSitePackages = new HashSet<>();
		Set<EPackage> eHelpPackages = new HashSet<>();
		for (String model: (Iterable<String>) models) {			
			URI uri = URI.createURI(model);
			if (uri.isRelative() && base != null && base.isHierarchical()) {
				uri = uri.resolve(base);
			}
			Resource resource = resourceSet.getResource(uri, true);
			TreeIterator<EObject> cit = resource.getAllContents();			
			while (cit.hasNext()) {
				EObject next = cit.next();
				if (next instanceof EPackage) {
					load((EPackage) next, eSitePackages, siteModeldocs.keySet());
					load((EPackage) next, eHelpPackages, helpModeldocs.keySet());
				} else if (next instanceof GenPackage) {
					load((GenPackage) next, eSitePackages, siteModeldocs.keySet());
					load((GenPackage) next, eHelpPackages, helpModeldocs.keySet());
				}
			}
		}
		
		List<EPackage> eSitePackagesList = new ArrayList<>(eSitePackages);
		eSitePackagesList.sort((a,b) -> a.getName().equals(b.getName()) ? a.getNsURI().compareTo(b.getNsURI()) : a.getName().compareTo(b.getName()) );
		
		List<EPackage> eHelpPackagesList = new ArrayList<>(eHelpPackages);
		eHelpPackagesList.sort((a,b) -> a.getName().equals(b.getName()) ? a.getNsURI().compareTo(b.getNsURI()) : a.getName().compareTo(b.getName()) );
		
		int totalWork = eSitePackages.size() + eHelpPackages.size();
		if (siteOutput != null) {
			totalWork += siteOutput.members().length + 2;
		}
		if (helpOutput != null) {
			totalWork += helpOutput.members().length + 2;
		}		
				
		Object apidocs = specMap.get("apidocs");
		if (apidocs instanceof Collection) {
			totalWork += ((Collection<?>) apidocs).size();
		}
		
		SubMonitor subMonitor = SubMonitor.convert(monitor, totalWork);
		
		// Cleanup 
		if (siteOutput != null) {
			for (IResource member: siteOutput.members()) {
				member.delete(false, subMonitor.split(1));
			}
		}
		if (helpOutput != null) {
			for (IResource member: helpOutput.members()) {
				member.delete(false, subMonitor.split(1));
			}
		}		

		Bundle webResourcesBundle = Platform.getBundle("org.nasdanika.web.resources");
		List<String> webResourcesPaths = new ArrayList<String>();
		
		collectResources(webResourcesBundle, "/bootstrap/", webResourcesPaths);
		collectResources(webResourcesBundle, "/font-awesome/", webResourcesPaths);
		collectResources(webResourcesBundle, "/css/", webResourcesPaths);
		collectResources(webResourcesBundle, "/highlight/", webResourcesPaths);
		collectResources(webResourcesBundle, "/jstree/", webResourcesPaths);
		collectResources(webResourcesBundle, "/js/", webResourcesPaths);
		collectResources(webResourcesBundle, "/images/", webResourcesPaths);		
		collectResources(webResourcesBundle, "/img/", webResourcesPaths);		
		
		// Apidocs
		Map<String, String> apidocLocations = new ConcurrentHashMap<>();
		if (apidocs instanceof Collection) {
			for (Object apidoc: (Collection<?>) apidocs) {
				if (apidoc instanceof String) {
					subMonitor.setTaskName("Downloading package list from "+apidoc);
					String normalizedLocation = (String) apidoc;
					if (!normalizedLocation.endsWith("/")) {
						normalizedLocation += "/";
					}
					try {
						URL packageListURL = new URL(normalizedLocation+"package-list");
						HttpURLConnection packageListConnection = (HttpURLConnection) packageListURL.openConnection();
						int responseCode = packageListConnection.getResponseCode();
						if (responseCode==HttpURLConnection.HTTP_OK) {
							try (BufferedReader br = new BufferedReader(new InputStreamReader(packageListConnection.getInputStream()))){
								String line;
								while ((line = br.readLine()) != null) {
									apidocLocations.put(line.trim(), normalizedLocation);
								}
							}
						} else {
							System.err.println("[WARN] Could not download package list from "+packageListURL+", response code: "+responseCode+", response message: "+packageListConnection.getResponseMessage());
						}
					} catch (Exception e) {
						System.err.println("[WARN] Could not download package list from "+apidoc+" - "+e);
					}
				}
				subMonitor.worked(1);
			}			
		}
		
		// Web resources
		if (siteOutput == null) {
			subMonitor.worked(1);			
		} else {
			SubMonitor rMon = SubMonitor.convert(subMonitor.split(1), webResourcesPaths.size()+1);
			rMon.setTaskName("Copying site resources");
			IFolder rFolder = createFolder(siteOutput, "resources", rMon.split(1));
			for (String path: webResourcesPaths) {
				rMon.subTask(path);
				createFile(rFolder, path, webResourcesBundle.getEntry(path).openStream(), rMon.split(1));
			}
			
			// left-panel.js
			createFile(rFolder, "js/left-panel.js", GenerateEcoreDocumentationAction.class.getResourceAsStream("left-panel.js"), rMon.split(1));
		}
		
		if (helpOutput == null) {
			subMonitor.worked(1);			
		} else {
			SubMonitor rMon = SubMonitor.convert(subMonitor.split(1), webResourcesPaths.size()+1);
			rMon.setTaskName("Copying help resources");
			IFolder rFolder = createFolder(helpOutput, "resources", rMon.split(1));
			for (String path: webResourcesPaths) {
				rMon.subTask(path);
				createFile(rFolder, path, webResourcesBundle.getEntry(path).openStream(), rMon.split(1));
			}
		}		
		
		// Images
		Bundle docEcoreBundle = Platform.getBundle("org.nasdanika.doc.ecore");
		List<String> imagesPaths = new ArrayList<String>();		
		collectResources(docEcoreBundle, "/images/", imagesPaths);
		
		if (siteOutput == null) {
			subMonitor.worked(1);			
		} else {
			SubMonitor rMon = SubMonitor.convert(subMonitor.split(1), imagesPaths.size()+1);
			rMon.setTaskName("Copying site images");
			IFolder rFolder = createFolder(siteOutput, "resources", rMon.split(1));
			for (String path: imagesPaths) {
				rMon.subTask(path);
				createFile(rFolder, path, docEcoreBundle.getEntry(path).openStream(), rMon.split(1));
			}
		}
		
		if (helpOutput == null) {
			subMonitor.worked(1);			
		} else {
			SubMonitor rMon = SubMonitor.convert(subMonitor.split(1), imagesPaths.size()+1);
			rMon.setTaskName("Copying help images");
			IFolder rFolder = createFolder(helpOutput, "resources", rMon.split(1));
			for (String path: imagesPaths) {
				rMon.subTask(path);
				createFile(rFolder, path, docEcoreBundle.getEntry(path).openStream(), rMon.split(1));
			}
		}		
		
		if (helpOutput != null) {
			Map<EClassifier, String> helpFileNameMap = generateHelp(eHelpPackagesList, helpModeldocs, apidocLocations, helpOutput, setDerived, subMonitor);
			
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document toc = dBuilder.newDocument();
			
			int idx = helpOutputStr.indexOf("/", 1);
			String prefix = idx == -1 ? "" : helpOutputStr.substring(idx+1)+"/";
			
			Element root = toc.createElement("toc");			
			toc.appendChild(root);
			Object tocSpec = specMap.get("toc");
			if (tocSpec instanceof Map) {
				for (Entry<String, String> tae: ((Map<String, String>) tocSpec).entrySet()) {
					root.setAttribute(tae.getKey(), tae.getValue());
				}
			}
			
			for (EPackage ePackage: eHelpPackagesList) {
				if (ePackage.getESuperPackage() == null) {
					root.appendChild(createEPackageTopic(toc, prefix, ePackage, hasDuplicateName(ePackage, eHelpPackagesList), helpFileNameMap));
				}
			}
			
		    // Use a Transformer for output
		    TransformerFactory tFactory = TransformerFactory.newInstance();
		    Transformer transformer = tFactory.newTransformer();
		    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		    
		    DOMSource source = new DOMSource(toc);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
		    StreamResult out = new StreamResult(baos);
		    transformer.transform(source, out);
			baos.close();
			
			InputStream content = new ByteArrayInputStream(baos.toByteArray());
			IFile tocFile = createFile(helpOutput, "toc.xml", content, subMonitor.split(1));
			tocFile.setDerived(setDerived, subMonitor.split(1));			
		}
		
		
		if (siteOutput != null) {
			Map<EClassifier, String> siteFileNameMap = generateSite(eSitePackagesList, siteModeldocs, apidocLocations, siteOutput, setDerived, subMonitor);
			
			final JSONObject idMap = new JSONObject();
			JSONArray tree = new JSONArray();
			for (EPackage ePackage: eSitePackagesList) {
				if (ePackage.getESuperPackage() == null) {
					tree.put(createEPackageToc(ePackage, hasDuplicateName(ePackage, eSitePackagesList), idMap, siteFileNameMap));
				}
			}
			
			JSONObject toc = new JSONObject();
			toc.put("idMap", idMap);
			toc.put("tree", tree);
			
			InputStream tocContent = new ByteArrayInputStream(("define("+toc+")").getBytes(StandardCharsets.UTF_8));
			IFile tocFile = createFile(siteOutput, "toc.js", tocContent, subMonitor.split(1));
			tocFile.setDerived(setDerived, subMonitor.split(1));						
			
			InputStream content = new ByteArrayInputStream(generateIndexHtml().getBytes(StandardCharsets.UTF_8));
			IFile indexFile = createFile(siteOutput, "index.html", content, subMonitor.split(1));
			indexFile.setDerived(setDerived, subMonitor.split(1));						
		}
		
	}

	private Map<EClassifier, String> generateSite(
			List<EPackage> eSitePackagesList,
			Map<String, String> siteModeldocs,
			Map<String, String> apidocLocations,
			IFolder siteOutput, 
			boolean setDerived,
			SubMonitor subMonitor) throws CoreException, IOException {
		
		Map<EClassifier, String> siteFileNameMap = new HashMap<EClassifier, String>();
		for (EPackage ePackage: eSitePackagesList) {
			List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
			eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
			SubMonitor packageMonitor = SubMonitor.convert(subMonitor.split(1), eClassifiers.size()*2+6);
			packageMonitor.setTaskName("Site, EPackage " + ePackage.getName() + " ("+ePackage.getNsURI()+")");
			String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
			
			IFolder packageSiteOutputFolder = createFolder(siteOutput, packageFolderName, packageMonitor.split(1));
			packageSiteOutputFolder.setDerived(setDerived, packageMonitor.split(1));
			
			for (EClassifier eClassifier: eClassifiers) {
				packageMonitor.subTask(eClassifier.eClass().getName()+" "+eClassifier.getName());
				// classifier doc
				
				String siteClassifierDocumentation = null;
				String siteClassifierDocumentationFileName = null;
				
				if (eClassifier instanceof EClass) {				
					EClassDocumentationGenerator eClassDocumentationGenerator = createEClassDocumentationGenerator((EClass) eClassifier, true, siteModeldocs, apidocLocations);
					siteClassifierDocumentationFileName = eClassDocumentationGenerator.getNamedElementFileName(eClassifier);
					
					ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
					String cMap = eClassDocumentationGenerator.generateDiagram(false, null, 1, RelationshipDirection.both, true, true, imgContent);				
					IFile eClassSiteDiagramFile = createFile(packageSiteOutputFolder, siteClassifierDocumentationFileName+".png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
					eClassSiteDiagramFile.setDerived(setDerived, packageMonitor.split(1));
					
					siteClassifierDocumentation = eClassDocumentationGenerator.generateDocumentation(cMap);
					siteFileNameMap.put(eClassifier, siteClassifierDocumentationFileName);
				} else if (eClassifier instanceof EEnum) {
					EEnumDocumentationGenerator eEnumDocumentationGenerator = createEEnumDocumentationGenerator((EEnum) eClassifier, true, siteModeldocs, apidocLocations);
					siteClassifierDocumentationFileName = eEnumDocumentationGenerator.getNamedElementFileName(eClassifier);
					siteFileNameMap.put(eClassifier, siteClassifierDocumentationFileName);
					siteClassifierDocumentation = eEnumDocumentationGenerator.generateDocumentation(null);
				} else { // EDataType
					EDataTypeDocumentationGenerator eDataTypeDocumentationGenerator = createEDataTypeDocumentationGenerator((EDataType) eClassifier, true, siteModeldocs, apidocLocations);
					siteClassifierDocumentationFileName = eDataTypeDocumentationGenerator.getNamedElementFileName(eClassifier);
					siteFileNameMap.put(eClassifier, siteClassifierDocumentationFileName);
					siteClassifierDocumentation = eDataTypeDocumentationGenerator.generateDocumentation(null);
				}
				
				if (packageSiteOutputFolder == null) {
					packageMonitor.worked(2);
				} else {
					InputStream content = new ByteArrayInputStream(siteClassifierDocumentation.getBytes(StandardCharsets.UTF_8));
					IFile eClassifierSiteFile = createFile(packageSiteOutputFolder, siteClassifierDocumentationFileName+".html", content, packageMonitor.split(1));
					eClassifierSiteFile.setDerived(setDerived, packageMonitor.split(1));
				}
			}

			EPackageDocumentationGenerator ePacakgeDocumentationGenerator = createEPackageDocumentationGenerator(ePackage, true, siteModeldocs, apidocLocations);

			ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
			String cMap = ePacakgeDocumentationGenerator.generateDiagram(false, null, 0, RelationshipDirection.both, true, true, imgContent);				
			IFile ePackageSiteDiagramFile = createFile(packageSiteOutputFolder, "package-summary.png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
			ePackageSiteDiagramFile.setDerived(setDerived, packageMonitor.split(1));			
			
			String ePackageDocumentation = ePacakgeDocumentationGenerator.generateDocumentation(cMap);
			InputStream content = new ByteArrayInputStream(ePackageDocumentation.getBytes(StandardCharsets.UTF_8));
			IFile ePackageSiteFile = createFile(packageSiteOutputFolder, "package-summary.html", content, packageMonitor.split(1));
			ePackageSiteFile.setDerived(setDerived, packageMonitor.split(1));
			
		}
		return siteFileNameMap;
	}
	
	private Map<EClassifier, String> generateHelp(
			List<EPackage> eHelpPackagesList,
			Map<String, String> helpModeldocs, 
			Map<String, String> apidocLocations,
			IFolder helpOutput, 
			boolean setDerived,
			SubMonitor subMonitor) throws CoreException, IOException {
		
		Map<EClassifier, String> helpFileNameMap = new HashMap<EClassifier, String>();
		for (EPackage ePackage: eHelpPackagesList) {
			List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
			eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
			SubMonitor packageMonitor = SubMonitor.convert(subMonitor.split(1), eClassifiers.size()*4+11);
			packageMonitor.setTaskName("Help, EPackage " + ePackage.getName() + " ("+ePackage.getNsURI()+")");
			String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
			IFolder packageHelpOutputFolder = createFolder(helpOutput, packageFolderName, packageMonitor.split(1));
			packageHelpOutputFolder.setDerived(setDerived, packageMonitor.split(1));				
			
			for (EClassifier eClassifier: eClassifiers) {
				packageMonitor.subTask(eClassifier.eClass().getName()+" "+eClassifier.getName());
				// classifier doc
				
				String helpClassifierDocumentation = null;
				String helpClassifierDocumentationFileName = null;
				
				if (eClassifier instanceof EClass) {				
					EClassDocumentationGenerator eClassDocumentationGenerator = createEClassDocumentationGenerator((EClass) eClassifier, false, helpModeldocs, apidocLocations);
					helpClassifierDocumentationFileName = eClassDocumentationGenerator.getNamedElementFileName(eClassifier);
					
					ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
					String cMap = eClassDocumentationGenerator.generateDiagram(false, null, 1, RelationshipDirection.both, true, true, imgContent);						
					IFile eClassHelpDiagramFile = createFile(packageHelpOutputFolder, helpClassifierDocumentationFileName+".png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
					eClassHelpDiagramFile.setDerived(setDerived, packageMonitor.split(1));
					
					helpClassifierDocumentation = eClassDocumentationGenerator.generateDocumentation(cMap);
					helpFileNameMap.put(eClassifier, helpClassifierDocumentationFileName);
				} else if (eClassifier instanceof EEnum) {
					EEnumDocumentationGenerator eEnumDocumentationGenerator = createEEnumDocumentationGenerator((EEnum) eClassifier, false, helpModeldocs, apidocLocations);
					helpClassifierDocumentationFileName = eEnumDocumentationGenerator.getNamedElementFileName(eClassifier);
					helpFileNameMap.put(eClassifier, helpClassifierDocumentationFileName);
					helpClassifierDocumentation = eEnumDocumentationGenerator.generateDocumentation(null);
				} else { // EDataType
					EDataTypeDocumentationGenerator eDataTypeDocumentationGenerator = createEDataTypeDocumentationGenerator((EDataType) eClassifier, false, helpModeldocs, apidocLocations);
					helpClassifierDocumentationFileName = eDataTypeDocumentationGenerator.getNamedElementFileName(eClassifier);
					helpFileNameMap.put(eClassifier, helpClassifierDocumentationFileName);
					helpClassifierDocumentation = eDataTypeDocumentationGenerator.generateDocumentation(null);
				}
				
				String wrappedDocumentation = HTMLFactory.INSTANCE.interpolate(GenerateEcoreDocumentationAction.class.getResource("help-page-template.html"), "content", helpClassifierDocumentation);
				InputStream content = new ByteArrayInputStream(wrappedDocumentation.getBytes(StandardCharsets.UTF_8));
				IFile eClassifierHelpFile = createFile(packageHelpOutputFolder, helpClassifierDocumentationFileName+".html", content, packageMonitor.split(1));
				eClassifierHelpFile.setDerived(setDerived, packageMonitor.split(1));
			}

			EPackageDocumentationGenerator ePacakgeDocumentationGenerator = createEPackageDocumentationGenerator(ePackage, false, helpModeldocs, apidocLocations);

			ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
			String cMap = ePacakgeDocumentationGenerator.generateDiagram(false, null, 0, RelationshipDirection.both, true, true, imgContent);				
			IFile ePackageHelpDiagramFile = createFile(packageHelpOutputFolder, "package-summary.png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
			ePackageHelpDiagramFile.setDerived(setDerived, packageMonitor.split(1));				
			
			String ePackageDocumentation = ePacakgeDocumentationGenerator.generateDocumentation(cMap);
			String wrappedDocumentation = HTMLFactory.INSTANCE.interpolate(GenerateEcoreDocumentationAction.class.getResource("help-page-template.html"), "content", ePackageDocumentation);
			InputStream content = new ByteArrayInputStream(wrappedDocumentation.getBytes(StandardCharsets.UTF_8));
			IFile ePackageHelpFile = createFile(packageHelpOutputFolder, "package-summary.html", content, packageMonitor.split(1));
			ePackageHelpFile.setDerived(setDerived, packageMonitor.split(1));
			
		}
		return helpFileNameMap;
	}
	
	private String generateIndexHtml() {
		HTMLFactory htmlFactory = HTMLFactory.INSTANCE;
		ApplicationPanel appPanel = htmlFactory.applicationPanel()
				.style(Style.INFO) 
				.header("Model documentation") // TODO - Configurable.
				.headerLink("index.html")
				.style("margin-bottom", "0px")
				.id("docAppPanel");
		
		Table table = htmlFactory.table().style("margin-bottom", "0px");
		Row row = table.row();
		DocumentationPanelFactory documentationPanelFactory = new DocumentationPanelFactory(htmlFactory) {

			@Override
			protected Tag tocDiv() {
				return super.tocDiv().style("overflow-y", "scroll");
			}
			
		};
		row.cell(documentationPanelFactory.leftPanel()).id("left-panel").style("min-width", "17em");
		row.cell("")
			.id("splitter")
			.style("width", "5px")
			.style("min-width", "5px")
			.style("padding", "0px")
			.style("background", "#d9edf7")
			.style("border", "solid 1px #bce8f1")
			.style("cursor", "col-resize");
		row.cell(documentationPanelFactory.rightPanel()).id("right-panel");
				
		appPanel.contentPanel(
				table, 
				htmlFactory.tag(TagName.script, getClass().getResource("Splitter.js")),
				htmlFactory.tag(TagName.script, getClass().getResource("Scroller.js")),
				htmlFactory.tag(TagName.script, getClass().getResource("SetDimensions.js")));
		
		AutoCloseable app = htmlFactory.bootstrapRouterApplication(
				Theme.Default,
				"Documentation", 
				null, //"main/doc/index.html", 
				htmlFactory.fragment(
						// --- Stylesheets ---					
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/bootstrap/css/bootstrap.min.css"),							
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/bootstrap/css/bootstrap-theme.min.css"),							
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/font-awesome/css/font-awesome.min.css"),							
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/css/lightbox.css"),							
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/highlight/styles/github.css"),							
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/css/github-markdown.css"),							
						htmlFactory.tag(TagName.link)
							.attribute("rel", "stylesheet")
							.attribute("href", "resources/jstree/themes/default/style.min.css"),
							
						// --- Scripts ---
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/jquery-1.12.1.min.js"),
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/underscore-min.js"),
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/backbone-min.js"),
						htmlFactory.tag(TagName.script).attribute("src", "resources/bootstrap/js/bootstrap.min.js"),
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/d3.min.js"), 				
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/c3.min.js"),												
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/require.js"),
						htmlFactory.tag(TagName.script, htmlFactory.interpolate(getClass().getResource("require-config.js"), "base-url", "resources/js")),
						htmlFactory.tag(TagName.script).attribute("src", "resources/js/lightbox.min.js"),
						htmlFactory.tag(TagName.script).attribute("src", "resources/highlight/highlight.pack.js")), 				
				appPanel);
		
		return app.toString();
	}

	private EPackageDocumentationGenerator createEPackageDocumentationGenerator(EPackage ePackage, boolean rewriteURLs, Map<String, String> modeldocs, Map<String, String> apidocLocations) {
		EPackageDocumentationGenerator ePacakgeDocumentationGenerator = new EPackageDocumentationGenerator(ePackage) {
			
			protected String getIconsBaseLocation() {
				return rewriteURLs ? SITE_ICONS_BASE_LOCATION : HELP_ICONS_BASE_LOCATION;
			}
			
			protected String markdownToHtml(String markdown) {
				return GenerateEcoreDocumentationAction.this.markdownToHtml(markdown, rewriteURLs);
			}
			
			protected String preProcessMarkdown(String markdown) {
				return GenerateEcoreDocumentationAction.this.preProcessMarkdown(markdown);							
			}
										
			@Override
			protected String getDiagramImageLocation() {
				if (rewriteURLs) {
					return getNamedElementFileName(getModelElement())+"/"+super.getDiagramImageLocation();
				}
				
				return super.getDiagramImageLocation();
			}
			
			protected String getEPackageLocation(EPackage ePackage) {
				if (ePackage == null) {
					return null;
				}

				String modeldoc = modeldocs.get(ePackage.getNsURI());
				if (modeldoc != null) {
					if (!modeldoc.endsWith("/")) {
						modeldoc += "/";
					}
					return HTMLFactory.INSTANCE.interpolate(modeldoc, ENCODED_PACKAGE_NS_URI_TOKEN, getNamedElementFileName(ePackage));
				}
				
				return rewriteURLs ? "#router/doc-content/"+getNamedElementFileName(ePackage)+"/" : super.getEPackageLocation(ePackage);
			}
			
		};
		return ePacakgeDocumentationGenerator;
	}

	private EDataTypeDocumentationGenerator createEDataTypeDocumentationGenerator(EDataType eDataType, boolean rewriteURLs, Map<String, String> modeldocs, Map<String, String> apidocLocations) {
		EDataTypeDocumentationGenerator eDataTypeDocumentationGenerator = new EDataTypeDocumentationGenerator(eDataType) {
			
			protected String getIconsBaseLocation() {
				return rewriteURLs ? SITE_ICONS_BASE_LOCATION : HELP_ICONS_BASE_LOCATION;
			}
			
			protected String markdownToHtml(String markdown) {
				return GenerateEcoreDocumentationAction.this.markdownToHtml(markdown, rewriteURLs);
			}
			
			protected String preProcessMarkdown(String markdown) {
				return GenerateEcoreDocumentationAction.this.preProcessMarkdown(markdown);							
			}
			
			protected String getEPackageLocation(EPackage ePackage) {
				if (ePackage == null) {
					return null;
				}

				String modeldoc = modeldocs.get(ePackage.getNsURI());
				if (modeldoc != null) {
					if (!modeldoc.endsWith("/")) {
						modeldoc += "/";
					}
					return HTMLFactory.INSTANCE.interpolate(modeldoc, ENCODED_PACKAGE_NS_URI_TOKEN, getNamedElementFileName(ePackage));
				}
				
				return rewriteURLs ? "#router/doc-content/"+getNamedElementFileName(ePackage)+"/" : super.getEPackageLocation(ePackage);
			}
			
			@Override
			protected String javaDocLink(String className) {
				if (className != null) {
					int lastDot = className.lastIndexOf('.');
					if (lastDot > 0) {
						String url = apidocLocations.get(className.substring(0, lastDot));						
						if (url != null) {
							return "<a href=\""+url+"index.html?"+className.replace('.', '/')+".html\">"+className+"</a>";
						}
					}					
				}
				return super.javaDocLink(className);
			}
			
		};
		return eDataTypeDocumentationGenerator;
	}

	private EEnumDocumentationGenerator createEEnumDocumentationGenerator(EEnum eEnum, boolean rewriteURLs, Map<String, String> modeldocs, Map<String, String> apidocLocations) {
		EEnumDocumentationGenerator eEnumDocumentationGenerator = new EEnumDocumentationGenerator(eEnum) {
			
			protected String getIconsBaseLocation() {
				return rewriteURLs ? SITE_ICONS_BASE_LOCATION : HELP_ICONS_BASE_LOCATION;
			}
			
			protected String markdownToHtml(String markdown) {
				return GenerateEcoreDocumentationAction.this.markdownToHtml(markdown, rewriteURLs);
			}
			
			protected String preProcessMarkdown(String markdown) {
				return GenerateEcoreDocumentationAction.this.preProcessMarkdown(markdown);							
			}
									
			protected String getEPackageLocation(EPackage ePackage) {
				if (ePackage == null) {
					return null;
				}

				String modeldoc = modeldocs.get(ePackage.getNsURI());
				if (modeldoc != null) {
					if (!modeldoc.endsWith("/")) {
						modeldoc += "/";
					}
					return HTMLFactory.INSTANCE.interpolate(modeldoc, ENCODED_PACKAGE_NS_URI_TOKEN, getNamedElementFileName(ePackage));
				}
				
				return rewriteURLs ? "#router/doc-content/"+getNamedElementFileName(ePackage)+"/" : super.getEPackageLocation(ePackage);
			}
			
			@Override
			protected String javaDocLink(String className) {
				if (className != null) {
					int lastDot = className.lastIndexOf('.');
					if (lastDot > 0) {
						String url = apidocLocations.get(className.substring(0, lastDot));						
						if (url != null) {
							return "<a href=\""+url+"index.html?"+className.replace('.', '/')+".html\">"+className+"</a>";
						}
					}					
				}
				return super.javaDocLink(className);
			}
			
		};
		return eEnumDocumentationGenerator;
	}

	private EClassDocumentationGenerator createEClassDocumentationGenerator(EClass eClass, boolean rewriteURLs, Map<String, String> modeldocs, Map<String, String> apidocLocations) {
		EClassDocumentationGenerator eClassDocumentationGenerator = new EClassDocumentationGenerator(eClass) {
			
			protected String getIconsBaseLocation() {
				return rewriteURLs ? SITE_ICONS_BASE_LOCATION : HELP_ICONS_BASE_LOCATION;
			}
			
			protected String markdownToHtml(String markdown) {
				return GenerateEcoreDocumentationAction.this.markdownToHtml(markdown, rewriteURLs);
			}
			
			protected String preProcessMarkdown(String markdown) {
				return GenerateEcoreDocumentationAction.this.preProcessMarkdown(markdown);							
			}
			
			@Override
			protected String getDiagramImageLocation() {
				if (rewriteURLs) {
					return getNamedElementFileName(getModelElement().getEPackage()) +"/"+super.getDiagramImageLocation();
				}
				
				return super.getDiagramImageLocation();
			}
			
			protected String getEPackageLocation(EPackage ePackage) {
				if (ePackage == null) {
					return null;
				}
				String modeldoc = modeldocs.get(ePackage.getNsURI());
				if (modeldoc != null) {
					if (!modeldoc.endsWith("/")) {
						modeldoc += "/";
					}
					return HTMLFactory.INSTANCE.interpolate(modeldoc, ENCODED_PACKAGE_NS_URI_TOKEN, getNamedElementFileName(ePackage));
				}
				
				return rewriteURLs ? "#router/doc-content/"+getNamedElementFileName(ePackage)+"/" : super.getEPackageLocation(ePackage);
			}
			
			@Override
			protected String javaDocLink(String className) {
				if (className != null) {
					int lastDot = className.lastIndexOf('.');
					if (lastDot > 0) {
						String url = apidocLocations.get(className.substring(0, lastDot));						
						if (url != null) {
							return "<a href=\""+url+"index.html?"+className.replace('.', '/')+".html\">"+className+"</a>";
						}
					}					
				}
				return super.javaDocLink(className);
			}
			
		};
		return eClassDocumentationGenerator;
	}

	protected String preProcessMarkdown(String content) {
		List<MarkdownPreProcessor> preProcessors = new ArrayList<>();
		for (IConfigurationElement ce: Platform.getExtensionRegistry().getConfigurationElementsFor("org.nasdanika.help.extensions")) {
			// TODO renderers cache to improve performance?
			if ("markdown-pre-processor".equals(ce.getName())) {
				try {
					MarkdownPreProcessor preProcessor = ((MarkdownPreProcessor) ce.createExecutableExtension("class"));
					preProcessors.add(preProcessor);
				} catch (CoreException e) {
					System.err.println("Exception while creating markdown pre-processor");
					e.printStackTrace();
				}
			}
		}	
		
		if (preProcessors.isEmpty() || content == null || content.length()==0) {
			return "";			
		}
		
		MarkdownPreProcessor.Region.Chain chain = new MarkdownPreProcessor.Region.Chain() {
			
			@Override
			public String process(String content) {
				return preProcessMarkdown(content);
			}
			
		};
		
		List<MarkdownPreProcessor.Region> matchedRegions = new ArrayList<>();
		for (org.nasdanika.help.markdown.MarkdownPreProcessor pp: preProcessors) {
			matchedRegions.addAll(pp.match(content));
		}
		Collections.sort(matchedRegions, new Comparator<MarkdownPreProcessor.Region>() {

			@Override
			public int compare(MarkdownPreProcessor.Region r1, MarkdownPreProcessor.Region r2) {
				if (r1.getStart() == r2.getStart()) {
					if (r1.getEnd() == r2.getEnd()) {
						return r1.hashCode() - r2.hashCode();
					}
					return r2.getEnd() - r1.getEnd(); // Larger regions get precedence
				}
				
				return r1.getStart() - r2.getStart(); // Earlier regions get precedence
			}
		});
		
		StringBuilder out = new StringBuilder();
		int start = 0;
		for (MarkdownPreProcessor.Region region: matchedRegions) {
			int regionStart = region.getStart();
			if (regionStart>=start) {
				if (regionStart>start) {
					out.append(content.substring(start, regionStart));
					start = regionStart;
				}
				String result = region.process(chain);
				if (result != null) {
					out.append(result);
					start = region.getEnd();
				}
			}
		}
		if (start<content.length()) {
			out.append(content.substring(start));
		}
		return out.toString();
	}

	protected String markdownToHtml(String markdown, boolean rewriteURLs) {
		MarkdownLinkRenderer markdownLinkRenderer = new ExtensibleMarkdownLinkRenderer() {
			
			@Override
			protected URLRewriter getURLRewriter() {
				if (rewriteURLs) {
					return new URLRewriter() {
						
						@Override
						public String rewrite(String url) {
							
							// Simple check for absolute links.
							if (url != null && 
									(url.toLowerCase().startsWith("mailto:") 
											|| url.toLowerCase().startsWith("http://") 
											|| url.toLowerCase().startsWith("https://")
											|| url.startsWith("/"))) {
								
								return url;
							}
								
							// Convert relative links to #router ...
							return "#router/doc-content/"+url;
						}
					};
				}
				return super.getURLRewriter();
			}
			
		};
		return new PegDownProcessor(getMarkdownOptions()).markdownToHtml(preProcessMarkdown(markdown), markdownLinkRenderer);
	}
	
	protected int getMarkdownOptions() {
		return Extensions.ALL ^ Extensions.HARDWRAPS ^ Extensions.SUPPRESS_HTML_BLOCKS ^ Extensions.SUPPRESS_ALL_HTML;
	}

	private Element createEPackageTopic(Document document, String prefix, EPackage ePackage, boolean hasDuplicateName, Map<EClassifier, String> fileNameMap) {
		String pLabel = ePackage.getName();
		if (hasDuplicateName) {
			pLabel += " ("+ePackage.getNsURI()+")";
		}
		Element pTopic = document.createElement("topic");
		pTopic.setAttribute("label", pLabel);
		
		String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
		pTopic.setAttribute("href", prefix+packageFolderName+"/package-summary.html");

		List<EPackage> eSubPackages = new ArrayList<>(ePackage.getESubpackages());
		eSubPackages.sort((a,b) -> a.getName().compareTo(b.getName()));
		for (EPackage sp: eSubPackages) {
			pTopic.appendChild(createEPackageTopic(document, prefix, sp, false, fileNameMap)); // Don't care about checking duplicate name in sub-packages.
		}
		
		List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
		eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
		
		for (EClassifier eClassifier: eClassifiers) {
			Element cTopic = document.createElement("topic");
			cTopic.setAttribute("label", eClassifier.getName());
			pTopic.appendChild(cTopic);
			cTopic.setAttribute("href", prefix+packageFolderName+"/"+fileNameMap.get(eClassifier)+".html");					
		}
		return pTopic;
	}

	private JSONObject createEPackageToc(EPackage ePackage, boolean hasDuplicateName, JSONObject idMap, Map<EClassifier, String> fileNameMap) {
		String pLabel = ePackage.getName();
		if (hasDuplicateName) {
			pLabel += " ("+ePackage.getNsURI()+")";
		}
		JSONObject ret = new JSONObject();
		ret.put("text", pLabel);
		ret.put("icon", SITE_ICONS_BASE_LOCATION + "/EPackage.gif");
		
		String pid = String.valueOf(idMap.length());
		ret.put("id", pid);
		String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
		idMap.put(pid, "#router/doc-content/"+packageFolderName+"/package-summary.html");

		JSONArray children = new JSONArray();
		ret.put("children", children);
		
		List<EPackage> eSubPackages = new ArrayList<>(ePackage.getESubpackages());
		eSubPackages.sort((a,b) -> a.getName().compareTo(b.getName()));
		for (EPackage sp: eSubPackages) {
			children.put(createEPackageToc(sp, false, idMap, fileNameMap)); // Don't care about checking duplicate name in sub-packages.
		}
		
		List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
		eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
		
		for (EClassifier eClassifier: eClassifiers) {
			JSONObject cObj = new JSONObject();
			cObj.put("text", eClassifier.getName());
			children.put(cObj);
			String cid = String.valueOf(idMap.length());
			cObj.put("id", cid);
			idMap.put(cid, "#router/doc-content/"+packageFolderName+"/"+fileNameMap.get(eClassifier)+".html");					
			cObj.put("icon", SITE_ICONS_BASE_LOCATION + "/"+eClassifier.eClass().getName()+".gif");
		}
		return ret;
	}
		
	private static boolean hasDuplicateName(EPackage ePackage, Collection<EPackage> ePackages) {
		for (EPackage ep: ePackages) {
			if (ep != ePackage && ep.getName().equals(ePackage.getName())) {
				return true;
			}
		}
		return false;
	}

	private static void collectResources(Bundle bundle, String path, List<String> paths) {
		Enumeration<String> renum = bundle.getEntryPaths(path);
		while (renum != null && renum.hasMoreElements()) {
			String nextElement = renum.nextElement();
			if (nextElement.endsWith("/")) {
				collectResources(bundle, nextElement, paths);
			} else {
				paths.add(nextElement);
			}
		}
	}
	
	private boolean load(EPackage ePackage, Set<EPackage> ePackages, Set<String> excludedNsURIs) {
		if (ePackage != null && !excludedNsURIs.contains(ePackage.getNsURI()) && ePackages.add(ePackage)) {
			TreeIterator<EObject> cit = ePackage.eAllContents();
			while (cit.hasNext()) {
				EObject next = cit.next();
				if (next instanceof EClass) {
					for (EClass st: ((EClass) next).getESuperTypes()) {
						load(st.getEPackage(), ePackages, excludedNsURIs);
					}
				} else if (next instanceof ETypedElement) {
					EClassifier eType = ((ETypedElement) next).getEType();
					if (eType != null) {
						load(eType.getEPackage(), ePackages, excludedNsURIs);
					}
				}
			}		
			return true;
		}		
		return false;
	}
	
	private void load(GenPackage genPackage, Set<EPackage> ePackages, Set<String> excludedNsURIs) {
		EPackage ePackage = genPackage.getEcorePackage();
		if (load(ePackage, ePackages, excludedNsURIs)) {
			for (GenClassifier gc: genPackage.getGenClassifiers()) {
				EClassifier ec = gc.getEcoreClassifier();
				if (ec.getInstanceClassName() == null) {
					ec.setInstanceClassName(gc.getRawInstanceClassName());
				}
			}			
		}		
	}

	private static MultiStatus createMultiStatus(String msg, Throwable t) {
		List<Status> childStatuses = new ArrayList<>();

		for (StackTraceElement stackTrace : t.getStackTrace()) {
			childStatuses.add(new Status(IStatus.ERROR, Activator.PLUGIN_ID, stackTrace.toString()));
		}

		if (t.getCause() != null) {
			childStatuses.add(createMultiStatus("Caused by: " + t.getCause(), t.getCause()));
		}

		for (Throwable s : t.getSuppressed()) {
			childStatuses.add(createMultiStatus("Supressed: " + s, s.getCause()));
		}

		MultiStatus ms = new MultiStatus(Activator.PLUGIN_ID, IStatus.ERROR,	childStatuses.toArray(new Status[childStatuses.size()]), msg, t);

		return ms;
	}

	@Override
	public void selectionChanged(IAction action, ISelection selection) {
		selectedFiles.clear();
		Iterator<?> theSet = ((IStructuredSelection) selection).iterator();
		while (theSet.hasNext()) {
			Object obj = theSet.next();
			if (obj instanceof IFile) {
				selectedFiles.add((IFile) obj);
			}
		}
	}

	@Override
	public void setActivePart(IAction action, IWorkbenchPart targetPart) {
		// NOP
	}
	
	public static IFolder createFolder(IContainer container, String path, SubMonitor monitor) throws CoreException {
		monitor.setWorkRemaining(2);
		int idx = path.lastIndexOf('/');
		if (idx != -1) {
			container = createFolder(container, path.substring(0, idx), monitor.split(1));
		}
		
		IFolder ret = container.getFolder(new Path(path.substring(idx+1)));
		if (!ret.exists()) {
			ret.create(false, true, monitor.split(1));
		}
		return ret;
	}
	
	public static IFile createFile(IContainer container, String path, InputStream content, SubMonitor monitor) throws CoreException {
		monitor.setWorkRemaining(2);
		int idx = path.lastIndexOf('/');
		if (idx != -1) {
			container = createFolder(container, path.substring(0, idx), monitor.split(1));
		}
		IFile ret = container.getFile(new Path(path.substring(idx+1)));
		ret.create(content, true, monitor.split(1));
		return ret;
	}		
	

}
