# Changelog

## master (unreleased)
	
## New Features
    
### Changes
	
### Bugs fixed

## 0.1.56 (16-05-2023)
	
## New Features
    
### Changes

	- Instrument def symbol meta, so (deftest... ) forms can be instrumented
	
### Bugs fixed
    
## 0.1.54 (19-04-2023)
	
## New Features
    
### Changes

	- instrument-namespaces-clj and instrument-namespaces-shadow-cljs accepts :prefixes? config
  
### Bugs fixed

	- Fix deep instrumentation, only follow vars that represent functions

## 0.1.52 (12/04/2023)
	
## New Features
    
### Changes

	- Updating tools.namespace
  
### Bugs fixed

## 0.1.50 (16-02-2023)
	
## New Features
    
### Changes
	    	
### Bugs fixed 

	- Fix specter com.rpl.specter/path macroexpansion issue by skipping path macroexpansion

## 0.1.48 (06-02-2023)
	
## New Features
    
### Changes
	
	- Improve hansel.api/instrument-var-clj deep instrumentation
	
### Bugs fixed 

## 0.1.46 (03-02-2023)
	
## New Features
    
### Changes

	- Remove type hints from symbols on trace-bind and trace-fn-call since they cause issues when re-evaluating on clojure 1.10
	
### Bugs fixed 
    
## 0.1.44 (02-02-2023)
	
## New Features
    
### Changes

	- Improve instrument-var-clj to support instrumenting vars like foo when (let [...] (defn foo [] ...))

### Bugs fixed 

## 0.1.42 (30-01-2023)
	
## New Features
    
### Changes

	- When re-evaluating vars in clj, use the namespace from the var meta, intead of the one from the var symbol.
      This fixes code instrumentation like where potemkin/import-vars is used.
	  
### Bugs fixed	

	- Fix instrument-var-clj resolve-sym which was causing issues with deep instrumentation

## 0.1.38 (29-01-2023)
	
## New Features
    
### Changes
	
### Bugs fixed
	
	- Fix instrumenting namespaces that ends with a comment and no new line
	- Fix instrument-var-clj for loaded files 

## 0.1.35 (26-12-2022)
	
## New Features
    
### Changes

	- Don't instrument record map forms which breaks some macroexpansions
	- Do not automatically convert big maps into sorted maps while instrumenting them
	
### Bugs fixed

## 0.1.31 (16-12-2022)
	
## New Features
    
### Changes

### Bugs fixed

- Fix important compilation error when ClojureScript isn't in the path

## 0.1.29 (09-12-2022)
	
## New Features

	- Add normalize-gensyms? undocumented config option
	
### Changes

### Bugs fixed

## 0.1.26 (08-12-2022)
	
## New Features

	- instrument-namespaces-* now returns the a map with :inst-fns and :affected-namespaces

### Changes

### Bugs fixed

## 0.1.24 (29-11-2022)
	
## New Features

### Changes

### Bugs fixed

	- Fix ClojureScript go blocks instrumentation
	
## 0.1.20 (23-11-2022)
	
## New Features

	- instrument-var-clj and instrument-var-shadow-cljs now accept :deep? true for recursively instrumenting referenced vars    
	
### Changes

### Bugs fixed
    
## 0.1.17 (14-11-2022)
