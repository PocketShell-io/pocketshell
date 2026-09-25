#!/usr/bin/env ruby
# Pin the stacked inline-terminal PR's packaged lifecycle journey to the
# JS-first Android check that runs for its base branch. This guard is cheap: it
# parses the workflow and mutates in-memory copies, without Docker or an emulator.

require "yaml"

WORKFLOW_PATH = File.expand_path("../.github/workflows/js-first-rewrite.yml", __dir__)
TESTS_WORKFLOW_PATH = File.expand_path("../.github/workflows/tests.yml", __dir__)
BASE_BRANCH = "issue-2857-live-dictation"
REWRITE_BRANCH = "rewrite/js-first-0.6.0"
BASE_CONDITION = "github.event_name == 'pull_request' && github.base_ref == '#{BASE_BRANCH}'"
BASE_EXPRESSION = "${{ #{BASE_CONDITION} }}"
EMULATOR_ACTION = "reactivecircus/android-emulator-runner@e89f39f1abbbd05b1113a29cf4db69e7540cae5a"
EMULATOR_STEP = "Run packaged JS smoke suite on API 35"
ARTIFACT_STEP = "Upload packaged APK and connected smoke results"
TEARDOWN_STEP = "Stop isolated Docker agents lane for inline terminal journey"

def load_workflow(path)
  parsed = YAML.safe_load_file(path, aliases: true)
  # Psych follows YAML 1.1 and reads the unquoted `on` key as boolean true.
  trigger = parsed.delete(true)
  parsed["on"] ||= trigger
  parsed
end

def nested_strings(value)
  case value
  when Hash then value.values.flat_map { |child| nested_strings(child) }
  when Array then value.flat_map { |child| nested_strings(child) }
  when String then [value]
  else []
  end
end

def named_step(job, name)
  steps = job.is_a?(Hash) ? job["steps"] : nil
  steps.is_a?(Array) ? steps.find { |step| step.is_a?(Hash) && step["name"] == name } : nil
end

def check_workflow(workflow)
  errors = []
  trigger = workflow["on"]
  pull_request = trigger.is_a?(Hash) ? trigger["pull_request"] : nil
  branches = pull_request.is_a?(Hash) ? pull_request["branches"] : nil
  unless branches.is_a?(Array) && branches.include?(BASE_BRANCH)
    errors << "pull_request branches must include #{BASE_BRANCH.inspect} so PR #2896's target receives this workflow"
  end
  unless branches.is_a?(Array) && branches.include?(REWRITE_BRANCH)
    errors << "pull_request branches must retain #{REWRITE_BRANCH.inspect}"
  end

  jobs = workflow["jobs"]
  job = jobs.is_a?(Hash) ? jobs["web-and-android"] : nil
  unless job.is_a?(Hash) && job["name"] == "JS checks and Android debug APK"
    errors << "the lifecycle journey must run in the JS-first Android check job"
    return errors
  end

  steps = job["steps"].is_a?(Array) ? job["steps"] : []
  checker = named_step(job, "Check fail-closed inline-terminal journey CI wiring")
  checker_run = checker.is_a?(Hash) ? checker["run"].to_s : ""
  unless checker_run.include?("ruby scripts/check-inline-terminal-journey-wiring.rb --self-test") &&
         checker_run.include?("ruby scripts/check-inline-terminal-journey-wiring.rb\n")
    errors << "the JS-first Android check must run this wiring guard's self-test and real check"
  end

  checkout = named_step(job, "Checkout with pinned core source")
  unless checkout.is_a?(Hash) && checkout["uses"] == "actions/checkout@v6" &&
         checkout.dig("with", "fetch-depth").to_s == "0" && checkout.dig("with", "submodules") == "recursive"
    errors << "the shared JS-first setup must keep recursive submodules and full tag history"
  end

  node = named_step(job, "Set up Node.js 22")
  unless node.is_a?(Hash) && node["uses"] == "actions/setup-node@v5" && node.dig("with", "node-version").to_s == "22"
    errors << "the JS-first job must retain Node.js 22 setup"
  end
  pnpm = named_step(job, "Activate pnpm")
  unless pnpm.is_a?(Hash) && pnpm["run"].to_s.include?("pnpm@12.5.1")
    errors << "the JS-first job must retain the locked pnpm version"
  end
  jdk = named_step(job, "Set up JDK 21")
  unless jdk.is_a?(Hash) && jdk["uses"] == "actions/setup-java@v5" && jdk.dig("with", "java-version").to_s == "21"
    errors << "the JS-first job must retain JDK 21 setup"
  end
  android_sdk = named_step(job, "Set up Android SDK")
  unless android_sdk.is_a?(Hash) && android_sdk["uses"] == "android-actions/setup-android@v3"
    errors << "the JS-first job must retain Android SDK setup"
  end
  sdk_platform = named_step(job, "Install Android SDK platform")
  unless sdk_platform.is_a?(Hash) && sdk_platform["run"].to_s.include?("platforms;android-36") &&
         sdk_platform["run"].to_s.include?("build-tools;36.0.0")
    errors << "the JS-first job must install Android platform 36 and build-tools 36.0.0"
  end
  dependencies = named_step(job, "Install locked JS dependencies")
  unless dependencies.is_a?(Hash) && dependencies["run"] == "pnpm install --frozen-lockfile"
    errors << "the JS-first job must install the frozen JavaScript dependency set"
  end

  pool = named_step(job, "Start isolated Docker agents lane for inline terminal journey")
  unless pool.is_a?(Hash) && pool["id"] == "inline_terminal_agents" &&
         pool["if"] == BASE_CONDITION && pool["run"] == "scripts/agents-pool.sh up 2243"
    errors << "the isolated agents pool must start on port 2243 only for pull requests based on #{BASE_BRANCH}"
  end

  emulator = named_step(job, EMULATOR_STEP)
  emulator_with = emulator.is_a?(Hash) ? emulator["with"] : nil
  script = emulator_with.is_a?(Hash) ? emulator_with["script"].to_s : ""
  env = emulator.is_a?(Hash) ? emulator["env"] : nil
  unless emulator.is_a?(Hash) && emulator["uses"] == EMULATOR_ACTION &&
         emulator_with.is_a?(Hash) && emulator_with["api-level"].to_s == "35" &&
         emulator_with["target"] == "google_apis"
    errors << "the existing packaged smoke runner must retain the pinned API 35 Google APIs emulator"
  end
  unless script.include?("scripts/connected-js-smoke.sh --suffix i2855ci --test-only") &&
         script.include?("scripts/connected-js-lifecycle.sh --suffix i2855ci --port 2222")
    errors << "the existing packaged smoke and lifecycle happy path must remain in the emulator script"
  end
  unless script.include?('if [[ "${RUN_INLINE_TERMINAL_JOURNEY:-false}" == "true" ]]') &&
         script.include?("scripts/connected-js-composer-docker.sh") &&
         script.include?("--port 2243") &&
         script.include?('--session-prefix "$COMPOSER_SESSION_PREFIX"') &&
         script.include?("--suffix i2857ci")
    errors << "the emulator runner must conditionally invoke the packaged Docker composer journey against port 2243"
  end
  unless env.is_a?(Hash) && env["RUN_INLINE_TERMINAL_JOURNEY"] == BASE_EXPRESSION
    errors << "the composer journey invocation must be enabled only for PRs based on #{BASE_BRANCH}"
  end
  unless env.is_a?(Hash) && env["TMPDIR"] == "${{ runner.temp }}"
    errors << "the composer journey must store extracted evidence under runner.temp"
  end
  prefix = env.is_a?(Hash) ? env["COMPOSER_SESSION_PREFIX"].to_s : ""
  unless prefix == "js2857pr-${{ github.run_id }}-${{ github.run_attempt }}"
    errors << "the composer journey must use its valid run-id and attempt session prefix"
  end

  artifact = named_step(job, ARTIFACT_STEP)
  artifact_paths = artifact.dig("with", "path").to_s if artifact.is_a?(Hash)
  unless artifact.is_a?(Hash) && artifact["if"] == "always()" &&
         artifact["uses"].to_s.start_with?("actions/upload-artifact@") &&
         artifact_paths.to_s.include?("androidTest-results/connected/debug/TEST-*.xml") &&
         artifact_paths.to_s.include?("${{ runner.temp }}/pocketshell-js2857-js2857pr-${{ github.run_id }}-${{ github.run_attempt }}")
    errors << "the always-run artifact upload must include connected JUnit and host evidence from runner.temp"
  end

  teardown = named_step(job, TEARDOWN_STEP)
  teardown_if = teardown.is_a?(Hash) ? teardown["if"].to_s : ""
  unless teardown.is_a?(Hash) && teardown_if.start_with?("always()") &&
         teardown_if.include?("steps.inline_terminal_agents.conclusion != 'skipped'") &&
         teardown_if.include?(BASE_CONDITION) && teardown["run"] == "scripts/agents-pool.sh down 2243"
    errors << "the isolated agents pool must have an always-run port 2243 teardown scoped to the target PR"
  end

  pool_index = steps.index(pool)
  emulator_index = steps.index(emulator)
  artifact_index = steps.index(artifact)
  teardown_index = steps.index(teardown)
  unless pool_index && emulator_index && artifact_index && teardown_index &&
         pool_index < emulator_index && emulator_index < artifact_index && artifact_index < teardown_index
    errors << "the isolated pool must start before the emulator, with evidence upload and teardown after it"
  end

  job_timeout = job["timeout-minutes"].to_i
  emulator_timeout = emulator.is_a?(Hash) ? emulator["timeout-minutes"].to_i : 0
  if job_timeout < 120 || emulator_timeout < 90 || job_timeout <= emulator_timeout
    errors << "the JS-first job and API 35 runner need 120 and 90 minute timeouts respectively"
  end

  errors
end

def check_legacy_tests_workflow(workflow)
  errors = []
  trigger = workflow["on"]
  pull_request = trigger.is_a?(Hash) ? trigger["pull_request"] : nil
  branches = pull_request.is_a?(Hash) ? pull_request["branches"] : nil
  unless branches.is_a?(Array)
    errors << "Tests pull_request must keep an explicit branches filter so the stacked target cannot be included implicitly"
  end
  if branches.is_a?(Array) && branches.include?(BASE_BRANCH)
    errors << "Tests pull_request branches must exclude #{BASE_BRANCH.inspect}; that stacked path is gated by js-first-rewrite.yml"
  end

  jobs = workflow["jobs"]
  gate = jobs.is_a?(Hash) ? jobs["unit-gate"] : nil
  unless gate.is_a?(Hash)
    errors << "Tests workflow unit-gate is missing, so its legacy inline-terminal lane cannot be checked"
    return errors
  end
  unless gate["steps"].is_a?(Array)
    errors << "Tests workflow unit-gate steps must be an array so its legacy inline-terminal lane can be checked"
    return errors
  end
  steps = gate["steps"]
  legacy_steps = steps.select do |step|
    text = nested_strings(step).join(" ")
    text.match?(/inline[- ]terminal/i) ||
      text.match?(/connected-js-composer-docker\.sh|check-inline-terminal-journey-wiring\.rb/i)
  end
  unless legacy_steps.empty?
    names = legacy_steps.map { |step| step.is_a?(Hash) ? step["name"] : nil }.compact
    errors << "Tests unit-gate must not carry inline-terminal journey/wiring-check steps#{names.empty? ? "" : ": #{names.join(", ")}"}"
  end

  errors
end

def deep_copy(value)
  Marshal.load(Marshal.dump(value))
end

def expect_red(label, workflow, pattern)
  errors = check_workflow(workflow)
  unless errors.any? { |error| error.match?(pattern) }
    warn "FAIL: self-test #{label.inspect} did not reject the mutation for #{pattern.inspect}."
    warn errors.empty? ? "The mutated workflow passed." : errors.join("\n")
    return false
  end
  puts "PASS: self-test rejects #{label}"
  true
end

def expect_legacy_red(label, workflow, pattern)
  errors = check_legacy_tests_workflow(workflow)
  unless errors.any? { |error| error.match?(pattern) }
    warn "FAIL: self-test #{label.inspect} did not reject the legacy mutation for #{pattern.inspect}."
    warn errors.empty? ? "The mutated Tests workflow passed." : errors.join("\n")
    return false
  end
  puts "PASS: self-test rejects #{label}"
  true
end

def mutate_step(workflow, job_name, step_name)
  named_step(workflow.fetch("jobs").fetch(job_name), step_name)
end

def self_test(workflow, tests_workflow)
  errors = check_workflow(workflow) + check_legacy_tests_workflow(tests_workflow)
  abort "FAIL: baseline workflow is invalid:\n#{errors.join("\n")}" unless errors.empty?
  puts "PASS: self-test accepts both real workflows"

  cases = 0
  mutations = [
    ["missing stacked base trigger", /pull_request branches/, lambda { |w|
      w.fetch("on").fetch("pull_request").fetch("branches").delete(BASE_BRANCH)
    }],
    ["missing existing rewrite trigger", /retain.*rewrite\/js-first/, lambda { |w|
      w.fetch("on").fetch("pull_request").fetch("branches").delete(REWRITE_BRANCH)
    }],
    ["wiring guard removed from JS-first job", /must run this wiring guard/, lambda { |w|
      mutate_step(w, "web-and-android", "Check fail-closed inline-terminal journey CI wiring")["run"] = "echo no guard"
    }],
    ["Node setup changed", /retain Node\.js 22/, lambda { |w|
      mutate_step(w, "web-and-android", "Set up Node.js 22").fetch("with")["node-version"] = 20
    }],
    ["JDK setup changed", /retain JDK 21/, lambda { |w|
      mutate_step(w, "web-and-android", "Set up JDK 21").fetch("with")["java-version"] = "17"
    }],
    ["Android SDK setup removed", /retain Android SDK setup/, lambda { |w|
      mutate_step(w, "web-and-android", "Set up Android SDK")["uses"] = "echo no SDK setup"
    }],
    ["Android platform setup removed", /Android platform 36/, lambda { |w|
      mutate_step(w, "web-and-android", "Install Android SDK platform")["run"] = "sdkmanager platform-tools"
    }],
    ["isolated Docker pool missing", /isolated agents pool must start/, lambda { |w|
      mutate_step(w, "web-and-android", "Start isolated Docker agents lane for inline terminal journey")["run"] = "echo no pool"
    }],
    ["Docker pool not target-scoped", /isolated agents pool must start/, lambda { |w|
      mutate_step(w, "web-and-android", "Start isolated Docker agents lane for inline terminal journey")["if"] = "always()"
    }],
    ["API level reduced", /pinned API 35/, lambda { |w|
      mutate_step(w, "web-and-android", EMULATOR_STEP).fetch("with")["api-level"] = 34
    }],
    ["composer harness invocation removed", /conditionally invoke the packaged Docker composer journey/, lambda { |w|
      mutate_step(w, "web-and-android", EMULATOR_STEP).fetch("with")["script"] = "echo no composer"
    }],
    ["composer invocation no longer target-scoped", /enabled only for PRs based/, lambda { |w|
      mutate_step(w, "web-and-android", EMULATOR_STEP).fetch("env")["RUN_INLINE_TERMINAL_JOURNEY"] = "true"
    }],
    ["evidence moved off runner temp", /under runner\.temp/, lambda { |w|
      mutate_step(w, "web-and-android", EMULATOR_STEP).fetch("env").delete("TMPDIR")
    }],
    ["session prefix no longer unique", /run-id and attempt session prefix/, lambda { |w|
      mutate_step(w, "web-and-android", EMULATOR_STEP).fetch("env")["COMPOSER_SESSION_PREFIX"] = "js2857pr-local"
    }],
    ["failure upload removed", /always-run artifact upload/, lambda { |w|
      mutate_step(w, "web-and-android", ARTIFACT_STEP)["if"] = "success()"
    }],
    ["host evidence omitted", /always-run artifact upload/, lambda { |w|
      artifact = mutate_step(w, "web-and-android", ARTIFACT_STEP)
      artifact.fetch("with")["path"] = "android/app/build/outputs/androidTest-results/connected/debug/TEST-*.xml"
    }],
    ["host evidence uploaded outside runner temp", /host evidence from runner.temp/, lambda { |w|
      artifact = mutate_step(w, "web-and-android", ARTIFACT_STEP)
      artifact.fetch("with")["path"] = artifact.fetch("with").fetch("path").sub("${{ runner.temp }}", "/tmp")
    }],
    ["pool teardown removed", /always-run port 2243 teardown/, lambda { |w|
      mutate_step(w, "web-and-android", TEARDOWN_STEP)["run"] = "echo no cleanup"
    }],
    ["pool teardown not always-run or target-scoped", /always-run port 2243 teardown/, lambda { |w|
      mutate_step(w, "web-and-android", TEARDOWN_STEP)["if"] = "success()"
    }],
    ["pool teardown reordered before artifacts", /start before the emulator, with evidence upload and teardown after it/, lambda { |w|
      job_steps = w.fetch("jobs").fetch("web-and-android").fetch("steps")
      artifact_index = job_steps.index { |step| step.is_a?(Hash) && step["name"] == ARTIFACT_STEP }
      teardown_index = job_steps.index { |step| step.is_a?(Hash) && step["name"] == TEARDOWN_STEP }
      job_steps[artifact_index], job_steps[teardown_index] = job_steps[teardown_index], job_steps[artifact_index]
    }],
  ]

  mutations.each do |label, pattern, mutate|
    candidate = deep_copy(workflow)
    mutate.call(candidate)
    abort "FAIL: self-test #{label.inspect} unexpectedly passed." unless expect_red(label, candidate, pattern)
    cases += 1
  end

  legacy_mutations = [
    ["Tests workflow includes the stacked base trigger", /Tests pull_request branches must exclude/, lambda { |w|
      w.fetch("on").fetch("pull_request").fetch("branches") << BASE_BRANCH
    }],
    ["composer journey reintroduced into Tests unit-gate", /Tests unit-gate must not carry/, lambda { |w|
      w.fetch("jobs").fetch("unit-gate").fetch("steps") << {
        "name" => "Run packaged inline terminal journey on API 35",
        "run" => "scripts/connected-js-composer-docker.sh --port 2243",
      }
    }],
    ["inline-terminal wiring checker reintroduced into Tests unit-gate", /Tests unit-gate must not carry/, lambda { |w|
      w.fetch("jobs").fetch("unit-gate").fetch("steps") << {
        "name" => "Inline terminal Docker journey workflow wiring",
        "run" => "ruby scripts/check-inline-terminal-journey-wiring.rb",
      }
    }],
    ["Tests unit-gate steps become uninspectable", /unit-gate steps must be an array/, lambda { |w|
      w.fetch("jobs").fetch("unit-gate")["steps"] = nil
    }],
  ]

  legacy_mutations.each do |label, pattern, mutate|
    candidate = deep_copy(tests_workflow)
    mutate.call(candidate)
    abort "FAIL: self-test #{label.inspect} unexpectedly passed." unless expect_legacy_red(label, candidate, pattern)
    cases += 1
  end

  puts "PASS: workflow wiring self-test (#{cases} red mutations)"
end

begin
  workflow = load_workflow(WORKFLOW_PATH)
  tests_workflow = load_workflow(TESTS_WORKFLOW_PATH)
  if ARGV == ["--self-test"]
    self_test(workflow, tests_workflow)
  elsif ARGV.empty?
    errors = check_workflow(workflow) + check_legacy_tests_workflow(tests_workflow)
    abort "FAIL: inline-terminal journey CI wiring:\n#{errors.map { |error| "  - #{error}" }.join("\n")}" unless errors.empty?
    puts "PASS: inline-terminal Docker/API35 journey runs in the JS-first Android check for PRs based on #{BASE_BRANCH}; Tests excludes that branch and its unit-gate carries no legacy lane"
  else
    abort "Usage: ruby scripts/check-inline-terminal-journey-wiring.rb [--self-test]"
  end
rescue Psych::Exception => error
  abort "FAIL: could not parse workflow: #{error.message.lines.first.to_s.strip}"
end
