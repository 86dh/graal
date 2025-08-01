local root_ci = import '../ci.jsonnet';

local wasm_suite_root = root_ci.wasm_suite_root;

local graal_suite_root = root_ci.graal_suite_root;

{
  local common = (import "../../../ci/ci_common/common.jsonnet"),

  devkits:: common.devkits,

  tier1:: {
    targets+: ['tier1'],
  },
  tier2:: {
    targets+: ['tier2'],
  },
  tier3:: {
    targets+: ['tier3'],
  },
  tier4:: {
    targets+: ['tier4'],
    notify_groups:: ['wasm'],
  },

  postmerge:: {
    targets+: ['post-merge'],
    notify_groups:: ['wasm'],
  },

  daily:: {
    targets+: ['daily'],
    notify_groups:: ['wasm'],
  },

  weekly:: {
    targets+: ['weekly'],
    notify_groups:: ['wasm'],
  },

  monthly:: {
    targets+: ['monthly'],
    notify_groups:: ['wasm'],
  },

  ondemand:: {
    targets+: ['ondemand'],
  },

  deploy:: {
    targets+: ['deploy'],
  },

  bench:: {
    targets+: ['bench'],
  },

  bench_daily:: self.bench + self.daily,
  bench_weekly:: self.bench + self.weekly,
  bench_monthly:: self.bench + self.monthly,
  bench_ondemand:: self.bench + self.ondemand,

  linux_common:: {
    packages+: {
      llvm: '==8.0.1',
    },
  },

  linux_amd64:: common.linux_amd64 + self.linux_common,
  linux_aarch64:: common.linux_aarch64 + self.linux_common,

  darwin_aarch64:: common.darwin_aarch64,
  darwin_amd64:: common.darwin_amd64,

  windows_common:: {
    packages+: $.devkits["windows-" + self.jdk_name].packages,
  },

  windows_amd64:: common.windows_amd64 + self.windows_common,

  emsdk:: {
    downloads+: {
      EMSDK_DIR: {name: 'emsdk', version: '1.39.13', platformspecific: true},
    },
    environment+: {
      EMCC_DIR: '$EMSDK_DIR/emscripten/master/'
    }
  },

  ocaml_dune:: {
    downloads+: {
      OCAML_DIR: {name: 'ocaml-dune', version: '3.16.1', platformspecific: true},
    },
    environment+: {
      PATH: "$OCAML_DIR/bin:$PATH",
      OCAMLLIB: "$OCAML_DIR/lib/ocaml"
    },
  },

  nodejs:: {
    downloads+: {
      NODE: {name: 'node', version: 'v18.14.1', platformspecific: true},
    },
    environment+: {
      NODE_DIR: '${NODE}/bin',
      PATH: '${NODE}/bin:${PATH}',
    },
  },

  local gate_cmd      = ['mx', 'gate', '--strict-mode', '--tags', '${GATE_TAGS}'],
  local gate_cmd_full = ['mx', '--dynamicimports', graal_suite_root, 'gate', '--strict-mode', '--tags', '${GATE_TAGS}'],

  common:: {
    name_suffix:: (if 'jdk_name' in self then '-' + self.jdk_name else '') + '-' + self.os + '-' + self.arch,
  },

  setup_common:: self.common + {
    setup+: [
      ['cd', wasm_suite_root],
      ['mx', 'sversions'],
    ],
  },

  setup_emsdk:: self.setup_common + {
    setup+: [
      ['set-export', 'ROOT_DIR', ['pwd']],
      ['set-export', 'EM_CONFIG', '$ROOT_DIR/.emscripten-config'],
      ['mx', 'emscripten-init', '$EM_CONFIG', '$EMSDK_DIR']
    ],
  },

  gate_graalwasm:: self.setup_common + {
    run+: [
      gate_cmd,
    ],
    timelimit: '45:00',
  },

  gate_graalwasm_style:: self.eclipse_jdt + self.gate_graalwasm + {
    environment+: {
      GATE_TAGS: 'style,fullbuild',
    },
  },

  gate_graalwasm_full:: common.deps.wasm + self.setup_common + {
    run+: [
      gate_cmd_full
    ],
    timelimit: '1:00:00',
  },

  gate_graalwasm_emsdk_full:: self.wabt_emsdk + self.setup_emsdk + {
    run+: [
      gate_cmd_full
    ],
    timelimit: '45:00',
  },

  gate_graalwasm_ocaml_full:: self.gate_graalwasm_emsdk_full + self.ocaml_dune,

  gate_graalwasm_coverage:: self.wabt_emsdk + self.setup_emsdk + {
    environment+: {
      GATE_TAGS: 'buildall,coverage',
    },
    run+: [
      gate_cmd_full + ['--jacoco-omit-excluded', '--jacoco-relativize-paths', '--jacoco-omit-src-gen', '--jacoco-format', 'lcov', '--jacocout', 'coverage']
    ],
    teardown+: [
      ['mx', 'sversions', '--print-repositories', '--json', '|', 'coverage-uploader.py', '--associated-repos', '-'],
    ],
    timelimit: '1:30:00',
  },

  bench_graalwasm_emsdk_full:: self.wabt_emsdk + self.setup_emsdk + {
    environment+: {
      BENCH_RESULTS_FILE_PATH : 'bench-results.json',
    },
    setup+: [
      ['mx', '--dy', graal_suite_root, 'build', '--all'],
    ],
    run+: [
      [
        'scripts/${BENCH_RUNNER}',
        '${BENCH_RESULTS_FILE_PATH}',
        '${BENCH_VM}',
        '${BENCH_VM_CONFIG}',
        'bench-uploader.py',
      ]
    ],
    logs: ['bench-results.json'],
    capabilities+: ['x52'],
    timelimit: '1:00:00',
  },

  eclipse_jdt              :: common.deps.pylint + common.deps.eclipse + common.deps.jdt,
  wabt_emsdk               :: common.deps.wasm + self.emsdk,
  wabt_emsdk_ocamlbuild    :: common.deps.wasm + self.emsdk + self.ocaml_dune,

}
