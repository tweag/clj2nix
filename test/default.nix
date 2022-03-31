let
  pkgs = import <nixpkgs> {};

  clj2nixResult = import ../use.nix {
    jsonPath = ./deps.test.json;
    inherit pkgs;
  };

in pkgs.srcOnly rec {
  name = "clj2nixTest";

  src = clj2nixResult.homeDir;
}
