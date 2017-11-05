package org.nasdanika.docgen.ecore;

import java.awt.geom.GeneralPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.emf.codegen.ecore.genmodel.GenClassifier;
import org.eclipse.emf.codegen.ecore.genmodel.GenPackage;


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
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
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
import org.nasdanika.doc.ecore.EClassDocumentationGenerator;
import org.nasdanika.doc.ecore.EDataTypeDocumentationGenerator;
import org.nasdanika.doc.ecore.EEnumDocumentationGenerator;
import org.nasdanika.doc.ecore.EPackageDocumentationGenerator;
import org.nasdanika.doc.ecore.PlantUmlTextGenerator.RelationshipDirection;
import org.nasdanika.html.HTMLFactory;
import org.osgi.framework.Bundle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.Yaml;

public class GenerateEcoreDocumentationAction implements IObjectActionDelegate {

	private static final String ICONS_BASE_LOCATION = "../resources/images/";
	private List<IFile> selectedFiles = new ArrayList<>();
	
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
		
		Object output = specMap.get("output");
		if (output == null) {
			throw new GenerationException("output is not specified");
		}
		
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
		
		Set<EPackage> ePackages = new HashSet<>();
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
					load((EPackage) next, ePackages);
				} else if (next instanceof GenPackage) {
					GenPackage genPackage = (GenPackage) next;
					for (GenClassifier gc: genPackage.getGenClassifiers()) {
						EClassifier ec = gc.getEcoreClassifier();
						if (ec.getInstanceClassName() == null) {
							ec.setInstanceClassName(gc.getRawInstanceClassName());
						}
					}					
					load(genPackage.getEcorePackage(), ePackages);
				}
			}
		}
		
		List<EPackage> ePackagesList = new ArrayList<>(ePackages);
		ePackagesList.sort((a,b) -> a.getName().equals(b.getName()) ? a.getNsURI().compareTo(b.getNsURI()) : a.getName().compareTo(b.getName()) );
		
		int totalWork = ePackages.size();
		if (siteOutput != null) {
			totalWork += siteOutput.members().length + 2;
		}
		if (helpOutput != null) {
			totalWork += helpOutput.members().length + 2;
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
		
		// Packages
		for (EPackage ePackage: ePackagesList) {
			List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
			eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
			SubMonitor packageMonitor = SubMonitor.convert(subMonitor.split(1), eClassifiers.size()*4+10);
			packageMonitor.setTaskName("EPackage " + ePackage.getName() + "("+ePackage.getNsURI()+")");
			String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
			
			IFolder packageSiteOutputFolder = siteOutput == null ? null : createFolder(siteOutput, packageFolderName, packageMonitor.split(1));
			IFolder packageHelpOutputFolder = helpOutput == null ? null : createFolder(helpOutput, packageFolderName, packageMonitor.split(1));
			
			if (packageSiteOutputFolder == null) {
				packageMonitor.worked(1);
			}
			
			if (packageHelpOutputFolder == null) {
				packageMonitor.worked(1);
			}
			
			for (EClassifier eClassifier: eClassifiers) {
				monitor.subTask(eClassifier.eClass().getName()+" "+eClassifier.getName());
				// classifier doc
				
				String classifierDocumentation;
				if (eClassifier instanceof EClass) {
					EClassDocumentationGenerator eClassDocumentationGenerator = new EClassDocumentationGenerator((EClass) eClassifier) {
						
						protected String getIconsBaseLocation() {
							return ICONS_BASE_LOCATION;
						}
						
					};
					classifierDocumentation = eClassDocumentationGenerator.generateDocumentation();
				
					if (packageSiteOutputFolder != null) {
						ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
						eClassDocumentationGenerator.generateDiagram(false, null, 1, RelationshipDirection.both, true, true, imgContent);				
						IFile eClassSiteDiagramFile = createFile(packageSiteOutputFolder, eClassifier.getName()+".png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
						eClassSiteDiagramFile.setDerived(true, packageMonitor.split(1));
					}
					
					if (packageHelpOutputFolder != null) {
						ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
						eClassDocumentationGenerator.generateDiagram(false, null, 1, RelationshipDirection.both, true, true, imgContent);				
						IFile eClassHelpDiagramFile = createFile(packageHelpOutputFolder, eClassifier.getName()+".png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
						eClassHelpDiagramFile.setDerived(true, packageMonitor.split(1));
					}					
				} else if (eClassifier instanceof EEnum) {
					EEnumDocumentationGenerator eEnumDocumentationGenerator = new EEnumDocumentationGenerator((EEnum) eClassifier) {
						
						protected String getIconsBaseLocation() {
							return ICONS_BASE_LOCATION;
						}
						
					};
					classifierDocumentation = eEnumDocumentationGenerator.generateDocumentation();						
				} else { // EDataType
					EDataTypeDocumentationGenerator eDataTypeDocumentationGenerator = new EDataTypeDocumentationGenerator((EDataType) eClassifier) {
						
						protected String getIconsBaseLocation() {
							return ICONS_BASE_LOCATION;
						}
						
					};
					classifierDocumentation = eDataTypeDocumentationGenerator.generateDocumentation();												
				}
				
				if (packageSiteOutputFolder == null) {
					packageMonitor.worked(2);
				} else {
					InputStream content = new ByteArrayInputStream(classifierDocumentation.getBytes(StandardCharsets.UTF_8));
					IFile eClassifierSiteFile = createFile(packageSiteOutputFolder, eClassifier.getName()+".html", content, packageMonitor.split(1));
					eClassifierSiteFile.setDerived(true, packageMonitor.split(1));
				}
				
				if (packageHelpOutputFolder == null) {
					packageMonitor.worked(2);
				} else {
					String wrappedDocumentation = HTMLFactory.INSTANCE.interpolate(GenerateEcoreDocumentationAction.class.getResource("help-page-template.html"), "content", classifierDocumentation);
					InputStream content = new ByteArrayInputStream(wrappedDocumentation.getBytes(StandardCharsets.UTF_8));
					IFile eClassifierHelpFile = createFile(packageHelpOutputFolder, eClassifier.getName()+".html", content, packageMonitor.split(1));
					eClassifierHelpFile.setDerived(true, packageMonitor.split(1));
				}
			}

			// package doc			
			EPackageDocumentationGenerator ePacakgeDocumentationGenerator = new EPackageDocumentationGenerator(ePackage) {
				
				protected String getIconsBaseLocation() {
					return ICONS_BASE_LOCATION;
				}
				
			};
			String ePackageDocumentation = ePacakgeDocumentationGenerator.generateDocumentation();
			if (packageSiteOutputFolder == null) {
				packageMonitor.worked(4);
			} else {
				InputStream content = new ByteArrayInputStream(ePackageDocumentation.getBytes(StandardCharsets.UTF_8));
				IFile ePackageSiteFile = createFile(packageSiteOutputFolder, "package-summary.html", content, packageMonitor.split(1));
				ePackageSiteFile.setDerived(true, packageMonitor.split(1));
				
				ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
				ePacakgeDocumentationGenerator.generateDiagram(false, null, 0, RelationshipDirection.both, true, true, imgContent);				
				IFile ePackageSiteDiagramFile = createFile(packageSiteOutputFolder, "package-summary.png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
				ePackageSiteDiagramFile.setDerived(true, packageMonitor.split(1));
				
			}

			if (packageHelpOutputFolder == null) {
				packageMonitor.worked(4);
			} else {
				String wrappedDocumentation = HTMLFactory.INSTANCE.interpolate(GenerateEcoreDocumentationAction.class.getResource("help-page-template.html"), "content", ePackageDocumentation);
				InputStream content = new ByteArrayInputStream(wrappedDocumentation.getBytes(StandardCharsets.UTF_8));
				IFile ePackageHelpFile = createFile(packageHelpOutputFolder, "package-summary.html", content, packageMonitor.split(1));
				ePackageHelpFile.setDerived(true, packageMonitor.split(1));
				
				ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
				ePacakgeDocumentationGenerator.generateDiagram(false, null, 0, RelationshipDirection.both, true, true, imgContent);				
				IFile ePackageHelpDiagramFile = createFile(packageHelpOutputFolder, "package-summary.png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
				ePackageHelpDiagramFile.setDerived(true, packageMonitor.split(1));				
			}
			
		}
				
		// toc.xml
		if (helpOutput != null) {
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
			
			for (EPackage ePackage: ePackagesList) {
				String pLabel = ePackage.getName();
				if (hasDuplicateName(ePackage, ePackagesList)) {
					pLabel += " ("+ePackage.getNsURI()+")";
				}
				Element pTopic = toc.createElement("topic");
				pTopic.setAttribute("label", pLabel);
				root.appendChild(pTopic);
				
				String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
				pTopic.setAttribute("href", prefix+packageFolderName+"/package-summary.html");
				
				List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
				eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
				
				for (EClassifier eClassifier: eClassifiers) {
					Element cTopic = toc.createElement("topic");
					cTopic.setAttribute("label", eClassifier.getName());
					pTopic.appendChild(cTopic);
					cTopic.setAttribute("href", prefix+packageFolderName+"/"+eClassifier.getName()+".html");					
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
			IFile ePackageHelpFile = createFile(helpOutput, "toc.xml", content, subMonitor.split(1));
			ePackageHelpFile.setDerived(true, subMonitor.split(1));			
		}
		
		
		// Menu ... site, help		
		
		
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
	
	private void load(EPackage ePackage, Set<EPackage> ePackages) {
		if (ePackages.add(ePackage)) {
			TreeIterator<EObject> cit = ePackage.eAllContents();
			while (cit.hasNext()) {
				EObject next = cit.next();
				if (next instanceof EClass) {
					for (EClass st: ((EClass) next).getESuperTypes()) {
						load(st.getEPackage(), ePackages);
					}
				} else if (next instanceof ETypedElement) {
					EClassifier eType = ((ETypedElement) next).getEType();
					if (eType != null) {
						load(eType.getEPackage(), ePackages);
					}
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
