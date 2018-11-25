package org.nasdanika.docgen.ecore;

import org.nasdanika.html.FontAwesome.Spinner;
import org.nasdanika.html.HTMLFactory;
import org.nasdanika.html.Input;
import org.nasdanika.html.InputType;
import org.nasdanika.html.Tag;

/**
 * This class generates documentation panel HTML.
 * @author Pavel
 *
 */
public class DocumentationPanelFactory {

	protected HTMLFactory htmlFactory;

	public DocumentationPanelFactory(HTMLFactory htmlFactory) {
		this.htmlFactory = htmlFactory;
	}

	/**
	 * @return Tag for the left panel - tree, search.
	 */
	public Tag leftPanel() {
		Tag leftOverlay = htmlFactory.spinnerOverlay(Spinner.spinner).id("left-overlay").style("display", "none");				
		return htmlFactory.div(leftOverlay, tocSearchDiv(), tocDiv());
	}
	
	public Tag rightPanel() {
		return htmlFactory.div().id("doc-content");		
	}

	protected Tag tocDiv() {
		return htmlFactory.div().id("toc");
	}
	
	protected Tag tocSearchDiv() {
		Input searchText = htmlFactory.input(InputType.text).id("toc-search").style().width("100%").placeholder("Search the table of contents");
		return htmlFactory.div(searchText);
	}

}
