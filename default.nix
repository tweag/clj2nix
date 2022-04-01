{ system ? builtins.currentSystem }:
let
  lock = with builtins; fromJSON (readFile ./flake.lock);
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${lock.nodes.nixpkgs.locked.rev}.tar.gz";
    sha256 = lock.nodes.nixpkgs.locked.narHash;
  };
  pkgs = import nixpkgs {
    config = {};
    overlays = [];
    inherit system;
  };


in {
  tool = pkgs.callPackage ./clj2nix.nix {};
  importJSON = import ./use.nix pkgs;
}
