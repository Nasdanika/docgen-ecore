package org.nasdanika.docgen.ecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
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
					load((GenPackage) next, ePackages);
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
		collectResources(webResourcesBundle, "/img/", webResourcesPaths);		
		
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
		
		// Packages
		for (EPackage ePackage: ePackagesList) {
			List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
			eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
			SubMonitor packageMonitor = SubMonitor.convert(subMonitor.split(1), eClassifiers.size()*4+11);
			packageMonitor.setTaskName("EPackage " + ePackage.getName() + " ("+ePackage.getNsURI()+")");
			String packageFolderName = Hex.encodeHexString(ePackage.getNsURI().getBytes(StandardCharsets.UTF_8));
			
			IFolder packageSiteOutputFolder = siteOutput == null ? null : createFolder(siteOutput, packageFolderName, packageMonitor.split(1));
			IFolder packageHelpOutputFolder = helpOutput == null ? null : createFolder(helpOutput, packageFolderName, packageMonitor.split(1));
			
			if (packageSiteOutputFolder == null) {
				packageMonitor.worked(2);
			} else {
				packageSiteOutputFolder.setDerived(true, packageMonitor.split(1));
			}
			
			if (packageHelpOutputFolder == null) {
				packageMonitor.worked(2);
			} else {
				packageHelpOutputFolder.setDerived(true, packageMonitor.split(1));				
			}
			
			for (EClassifier eClassifier: eClassifiers) {
				monitor.subTask(eClassifier.eClass().getName()+" "+eClassifier.getName());
				// classifier doc
				
				String siteClassifierDocumentation = null;
				String helpClassifierDocumentation = null;
				if (eClassifier instanceof EClass) {				
					if (packageSiteOutputFolder != null) {
						EClassDocumentationGenerator eClassDocumentationGenerator = createEClassDocumentationGenerator((EClass) eClassifier, true);
						siteClassifierDocumentation = eClassDocumentationGenerator.generateDocumentation();
						ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
						eClassDocumentationGenerator.generateDiagram(false, null, 1, RelationshipDirection.both, true, true, imgContent);				
						IFile eClassSiteDiagramFile = createFile(packageSiteOutputFolder, eClassifier.getName()+".png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
						eClassSiteDiagramFile.setDerived(true, packageMonitor.split(1));
					}
					
					if (packageHelpOutputFolder != null) {
						EClassDocumentationGenerator eClassDocumentationGenerator = createEClassDocumentationGenerator((EClass) eClassifier, false);
						helpClassifierDocumentation = eClassDocumentationGenerator.generateDocumentation();
						ByteArrayOutputStream imgContent = new ByteArrayOutputStream();
						eClassDocumentationGenerator.generateDiagram(false, null, 1, RelationshipDirection.both, true, true, imgContent);				
						IFile eClassHelpDiagramFile = createFile(packageHelpOutputFolder, eClassifier.getName()+".png", new ByteArrayInputStream(imgContent.toByteArray()), packageMonitor.split(1));
						eClassHelpDiagramFile.setDerived(true, packageMonitor.split(1));
					}					
				} else if (eClassifier instanceof EEnum) {
					if (packageSiteOutputFolder != null) {
						siteClassifierDocumentation = createEEnumDocumentationGenerator((EEnum) eClassifier, true).generateDocumentation();
					}
					if (packageHelpOutputFolder != null) {
						helpClassifierDocumentation = createEEnumDocumentationGenerator((EEnum) eClassifier, false).generateDocumentation();
					}					
				} else { // EDataType
					if (packageSiteOutputFolder != null) {
						siteClassifierDocumentation = createEDataTypeDocumentationGenerator((EDataType) eClassifier, true).generateDocumentation();
					}
					
					if (packageHelpOutputFolder != null) {
						helpClassifierDocumentation = createEDataTypeDocumentationGenerator((EDataType) eClassifier, false).generateDocumentation();
					}
				}
				
				if (packageSiteOutputFolder == null) {
					packageMonitor.worked(2);
				} else {
					InputStream content = new ByteArrayInputStream(siteClassifierDocumentation.getBytes(StandardCharsets.UTF_8));
					IFile eClassifierSiteFile = createFile(packageSiteOutputFolder, eClassifier.getName()+".html", content, packageMonitor.split(1));
					eClassifierSiteFile.setDerived(true, packageMonitor.split(1));
				}
				
				if (packageHelpOutputFolder == null) {
					packageMonitor.worked(2);
				} else {
					String wrappedDocumentation = HTMLFactory.INSTANCE.interpolate(GenerateEcoreDocumentationAction.class.getResource("help-page-template.html"), "content", helpClassifierDocumentation);
					InputStream content = new ByteArrayInputStream(wrappedDocumentation.getBytes(StandardCharsets.UTF_8));
					IFile eClassifierHelpFile = createFile(packageHelpOutputFolder, eClassifier.getName()+".html", content, packageMonitor.split(1));
					eClassifierHelpFile.setDerived(true, packageMonitor.split(1));
				}
			}

			// package doc			
			if (packageSiteOutputFolder == null) {
				packageMonitor.worked(4);
			} else {
				EPackageDocumentationGenerator ePacakgeDocumentationGenerator = createEPackageDocumentationGenerator(ePackage, true);
				String ePackageDocumentation = ePacakgeDocumentationGenerator.generateDocumentation();
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
				EPackageDocumentationGenerator ePacakgeDocumentationGenerator = createEPackageDocumentationGenerator(ePackage, false);
				String ePackageDocumentation = ePacakgeDocumentationGenerator.generateDocumentation();
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
				if (ePackage.getESuperPackage() == null) {
					root.appendChild(createEPackageTopic(toc, prefix, ePackage, hasDuplicateName(ePackage, ePackagesList)));
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
			tocFile.setDerived(true, subMonitor.split(1));			
		}
		
		
		// Site index.html - tree - pkg, classifiers, splitter, content, router, title.
		if (siteOutput != null) {
			final JSONObject idMap = new JSONObject();
			JSONArray tree = new JSONArray();
			for (EPackage ePackage: ePackagesList) {
				if (ePackage.getESuperPackage() == null) {
					tree.put(createEPackageToc(ePackage, hasDuplicateName(ePackage, ePackagesList), idMap));
				}
			}
			
			JSONObject toc = new JSONObject();
			toc.put("idMap", idMap);
			toc.put("tree", tree);
			
			InputStream tocContent = new ByteArrayInputStream(("define("+toc+")").getBytes(StandardCharsets.UTF_8));
			IFile tocFile = createFile(siteOutput, "toc.js", tocContent, subMonitor.split(1));
			tocFile.setDerived(true, subMonitor.split(1));						
			
			InputStream content = new ByteArrayInputStream(generateIndexHtml().getBytes(StandardCharsets.UTF_8));
			IFile indexFile = createFile(siteOutput, "index.html", content, subMonitor.split(1));
			indexFile.setDerived(true, subMonitor.split(1));						
		}
		
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

	private EPackageDocumentationGenerator createEPackageDocumentationGenerator(EPackage ePackage, boolean rewriteURLs) {
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
					String packageFolderName = Hex.encodeHexString(getModelElement().getNsURI().getBytes(StandardCharsets.UTF_8));
					return packageFolderName+"/"+super.getDiagramImageLocation();
				}
				
				return super.getDiagramImageLocation();
			}
			
			protected String getEPackageLocation(EPackage ePackage) {
				return "#router/doc-content/"+Hex.encodeHexString(ePackage.getNsURI().getBytes(/* UTF-8? */))+"/";
			}
			
		};
		return ePacakgeDocumentationGenerator;
	}

	private EDataTypeDocumentationGenerator createEDataTypeDocumentationGenerator(EDataType eDataType, boolean rewriteURLs) {
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
				return "#router/doc-content/"+Hex.encodeHexString(ePackage.getNsURI().getBytes(/* UTF-8? */))+"/";
			}
			
		};
		return eDataTypeDocumentationGenerator;
	}

	private EEnumDocumentationGenerator createEEnumDocumentationGenerator(EEnum eEnum, boolean rewriteURLs) {
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
				return "#router/doc-content/"+Hex.encodeHexString(ePackage.getNsURI().getBytes(/* UTF-8? */))+"/";
			}
			
		};
		return eEnumDocumentationGenerator;
	}

	private EClassDocumentationGenerator createEClassDocumentationGenerator(EClass eClass, boolean rewriteURLs) {
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
					String packageFolderName = Hex.encodeHexString(getModelElement().getEPackage().getNsURI().getBytes(StandardCharsets.UTF_8));
					return packageFolderName+"/"+super.getDiagramImageLocation();
				}
				
				return super.getDiagramImageLocation();
			}
			
			protected String getEPackageLocation(EPackage ePackage) {
				return "#router/doc-content/"+Hex.encodeHexString(ePackage.getNsURI().getBytes(/* UTF-8? */))+"/";
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

	private Element createEPackageTopic(Document document, String prefix, EPackage ePackage, boolean hasDuplicateName) {
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
			pTopic.appendChild(createEPackageTopic(document, prefix, sp, false)); // Don't care about checking duplicate name in sub-packages.
		}
		
		List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
		eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
		
		for (EClassifier eClassifier: eClassifiers) {
			Element cTopic = document.createElement("topic");
			cTopic.setAttribute("label", eClassifier.getName());
			pTopic.appendChild(cTopic);
			cTopic.setAttribute("href", prefix+packageFolderName+"/"+eClassifier.getName()+".html");					
		}
		return pTopic;
	}

	private JSONObject createEPackageToc(EPackage ePackage, boolean hasDuplicateName, JSONObject idMap) {
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
			children.put(createEPackageToc(sp, false, idMap)); // Don't care about checking duplicate name in sub-packages.
		}
		
		List<EClassifier> eClassifiers = new ArrayList<>(ePackage.getEClassifiers());
		eClassifiers.sort((a,b) -> a.getName().compareTo(b.getName()));
		
		for (EClassifier eClassifier: eClassifiers) {
			JSONObject cObj = new JSONObject();
			cObj.put("text", eClassifier.getName());
			children.put(cObj);
			String cid = String.valueOf(idMap.length());
			cObj.put("id", cid);
			idMap.put(cid, "#router/doc-content/"+packageFolderName+"/"+eClassifier.getName()+".html");					
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
	
	private boolean load(EPackage ePackage, Set<EPackage> ePackages) {
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
			return true;
		}		
		return false;
	}
	
	private void load(GenPackage genPackage, Set<EPackage> ePackages) {
		EPackage ePackage = genPackage.getEcorePackage();
		if (load(ePackage, ePackages)) {
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
