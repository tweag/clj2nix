{ jsonPath, pkgs }:
let
  lib = pkgs.lib;

  jsonValue = lib.importJSON jsonPath;

  repos = lib.mapAttrsToList (name: value: value.url) jsonValue.repos;

  createMavenPaths = name: { artifactId, groupId, version, classifier, sha512, ... }:
    let
      dependencies = lib.filter (value: lib.elem name value.dependents) (lib.attrValues jsonValue.packages);
      nameSansExt = "${builtins.replaceStrings ["."] ["/"] groupId}/${artifactId}/${version}/${artifactId}-${version}";
      artifact = pkgs.fetchMavenArtifact {
        inherit groupId artifactId version classifier repos sha512;
      };
      pom = pkgs.writeText "pom-${groupId}-${artifactId}-${version}.xml" ''
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>${groupId}</groupId>
          <artifactId>${artifactId}</artifactId>
          <packaging>jar</packaging>
          <version>${version}</version>
          ${lib.optionalString (dependencies != []) ''
            <dependencies>
            ${lib.concatMapStringsSep "  " ({ artifactId, groupId, version, ... }: ''
                <dependency>
                  <groupId> ${groupId} </groupId>
                  <artifactId> ${artifactId} </artifactId>
                  <version> ${version} </version>
                </dependency>
              '') dependencies
             }
            </dependencies>
          ''}
        </project>
      '';
    in [
      {
        name = ".m2/repository/${nameSansExt}.jar";
        path = artifact.jar;
      }
      {
        name = ".m2/repository/${nameSansExt}.pom";
        path = pom;
      }
    ];

  /*
  # Structure comes from https://github.com/clojure/tools.gitlibs/blob/9f98af7631e34983d5b0886e1ab6eadc3856290b/src/main/clojure/clojure/tools/gitlibs/impl.clj#L54-L81
  _repos
  └── github.com
     └── clojure
        └── tools.build
           └── config # Checks for this file existing (this is the case for bare git repositories)
  libs
  └── io.github.clojure # namespace
     └── tools.build # name
        └── 7d40500863818c6f9a6e077b18db305d02149384 # rev
  */

  createGitPaths = name: { url, namespace, cleanUrl, rev, sha256, ... }: [
    {
      name = ".gitlibs/${cleanUrl}/config";
      path = pkgs.emptyFile;
    }
    {
      name = ".gitlibs/libs/${namespace}/${name}/${rev}";
      path = pkgs.fetchgit {
        inherit url rev sha256;
      };
    }
  ];

  createPackagePaths = name: package:
    {
      maven = createMavenPaths name package;
      git = createGitPaths name package; # TODO, .gitlibs
    }.${package.type} or (throw "Unknown package type: ${package.type}");

  homeDir = pkgs.linkFarm "clojure-deps" (lib.concatLists (lib.mapAttrsToList createPackagePaths jsonValue.packages));
in {
  inherit homeDir;
}
