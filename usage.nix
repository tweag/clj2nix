let

  clj2nixSource = fetchTarball { ... };

  clj2nix = import clj2nixSource {};

  clj2nixResult = clj2nix.importJSON {
    jsonPath = ./deps.lock.json;
  };

  # For nix-shell
  nativeBuildInputs = [
    clj2nix.tool
  ];

  installPhase = ''
    export HOME=/build
    ln -vs ${clj2nixResult.homeDir}/.{m2,gitlibs} $HOME/
    ...
  '';
in null
