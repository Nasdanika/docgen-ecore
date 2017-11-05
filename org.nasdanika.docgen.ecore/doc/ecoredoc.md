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

* ``models`` - a list of relative paths to models to generate documentation for. All referenced models will also be included.
* ``api`` - a list of URL's of JavaDoc sites to resolve JavaDoc links. Optional.
* ``output`` - outputs to generate
  * ``site`` - static HTML site. Optional.
  * ``help`` - Eclipse Help. Optional. 
* ``toc`` - relevant only for Eclipse help generation. Contains ``toc`` element attributes such as ``label`` or ``link_to``.  

To generate documentation right-click on the specification file and select "Generate Ecore documentation" menu item.

For Eclipse help register the generated ``toc.xml`` with the help system.

Screenshots - static site and help...

markdown rendering

Plant UML

uml plug-in

GraphViz