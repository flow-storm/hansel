.PHONY: clean test-clj test-cljs test-all lint

clean:
	clj -T:build clean

test-clj:
	clj -M:test:test-clj unit-clj

test-cljs:
	rm .cljs_node_repl -rf; clj -M:test:test-cljs unit-cljs

test-all: test-clj test-cljs

lint:
	clj-kondo --config .clj-kondo/config.edn --lint src test

hansel.jar:
	clj -T:build jar

install: hansel.jar
	mvn install:install-file -Dfile=target/hansel.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/hansel/pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=target/hansel.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/hansel/pom.xml -Durl=https://clojars.org/repo
