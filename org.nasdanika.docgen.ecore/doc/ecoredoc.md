# Ecore Documentation Generation

Nasdanika Ecore Documentation Generator plug-in allows to generate static HTML and Eclipse help documentation for Ecore models. The generator treats documentation annotations content as
[markdown](https://daringfireball.net/projects/markdown/syntax). 

To generate documentation you will need to create a specification file with a predefined name ``ecore-docgen.yml``. The structure of the file is outlined below: 

```yaml
models:
  - relative path to model 1
  - relative path to model 2
apidocs:
  - url of apidoc site 1
  - url of apidoc site 2
output:
  site: absolute workspace path to a folder where a static site documentation shall be generated
  help: absolute workspace path to a folder where toc.xml and help content shall be generated
toc:
  label: TOC label
  link_to: Where to link       
```

* ``models`` - a list of relative paths to models to generate documentation for - ``.ecore`` or ``.genmodel``. All referenced models will also be included. When generator models (``.genmodel``) are used the generated documentation includes Java type information. 
* ``api`` - a list of URL's of JavaDoc sites to resolve JavaDoc links. Optional.
* ``output`` - outputs to generate
  * ``site`` - static HTML site. Optional.
  * ``help`` - Eclipse Help. Optional. 
* ``toc`` - relevant only for Eclipse help generation. Contains ``toc`` element attributes such as ``label`` or ``link_to``.

Example: 

```yaml
models:
  - ../CodegenExamples/model/Sample.ecore
  
output:
  site: /CodegenExamples/site   
  help: /CodegenExamples/help     

toc:
  label: Nasdanika
  link_to: "../org.nasdanika.help/toc.xml#Nasdanika"  
```  

To generate documentation right-click on the specification file and select "Generate Ecore documentation" menu item.

For Eclipse help register the generated ``toc.xml`` with the help system.

Please note that the generated site documentation uses AJAX and therefore cannot be served over ``file://`` protocol. 

## Requirements

The generator uses [PlantUML](http://plantuml.com/) to generate package and class context diagrams. Therefore it [requires](http://plantuml.com/graphviz-dot) [GraphViz](https://www.graphviz.org/).

## Roadmap

* PlantUML plug-in.
* Other extensions. 