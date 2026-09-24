#!/usr/bin/env ruby
# Pin the stacked inline-terminal PR's packaged lifecycle journey to the
# existing required `Unit tests` check. This guard runs with the static guards
# on every Tests workflow invocation, without an emulator or Docker daemon.

require "yaml"

WORKFLOW_PATH = File.expand_path("../.github/workflows/tests.yml", __dir__)
BASE_BRANCH = "issue-2857-live-dictation"
BASE_CONDITION = "github.event_name == 'pull_request' && github.base_ref == '#{BASE_BRANCH}'"
EMULATOR_ACTION = "reactivecircus/android-emulator-runner@e89f39f1abbbd05b1113a29cf4db69e7540cae5a"

def load_workflow(path)
  parsed = YAML.safe_load_file(path, aliases: true)
  # Psych follows YAML 1.1 and reads the unquoted `on` key as boolean true.
  trigger = parsed.delete(true)
  parsed["on"] ||= trigger
  parsed
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
    errors << "Tests pull_request branches must include #{BASE_BRANCH.inspect} (the PR base filter)"
  end

  jobs = workflow["jobs"]
  gate = jobs.is_a?(Hash) ? jobs["unit-gate"] : nil
  unless gate.is_a?(Hash) && gate["name"] == "Unit tests"
    errors << "the journey must be inside the existing unit-gate job named 'Unit tests'"
    return errors
  end
  needs = gate["needs"]
  unless needs.is_a?(Array) && needs.include?("unit") && needs.include?("guards-static")
    errors << "unit-gate must retain dependencies on the JVM unit job and blocking static guards"
  end
  gate_steps = gate["steps"].is_a?(Array) ? gate["steps"] : []
  rollup_index = gate_steps.index { |step| step.is_a?(Hash) && step["name"] == "Require every unit-lane job to have succeeded" }

  static_guards = jobs["guards-static"]
  static_guard_step = named_step(static_guards, "Inline terminal Docker journey workflow wiring")
  static_guard_run = static_guard_step.is_a?(Hash) ? static_guard_step["run"].to_s : ""
  unless static_guard_run.include?("ruby scripts/check-inline-terminal-journey-wiring.rb --self-test") &&
         static_guard_run.include?("ruby scripts/check-inline-terminal-journey-wiring.rb\n")
    errors << "the workflow wiring guard must run its self-test and real check in blocking guards-static"
  end

  target_steps = [
    "Checkout full history for inline terminal journey",
    "Set up Node.js 22 for inline terminal journey",
    "Activate pnpm for inline terminal journey",
    "Set up JDK 21 for inline terminal journey",
    "Set up Android SDK for inline terminal journey",
    "Install Android SDK platform for inline terminal journey",
    "Install locked JS dependencies for inline terminal journey",
    "Check emulator action pin for inline terminal journey",
    "Enable KVM for inline terminal journey",
    "Free disk space for API 35 inline terminal journey",
    "Repair Android SDK tools for inline terminal journey",
    "Start isolated Docker agents lane for inline terminal journey",
    "Run packaged inline terminal journey on API 35",
  ]
  steps = target_steps.to_h do |name|
    step = named_step(gate, name)
    unless step.is_a?(Hash) && step["if"] == BASE_CONDITION
      errors << "#{name.inspect} must run only for pull requests based on #{BASE_BRANCH}"
    end
    [name, step]
  end

  checkout = steps["Checkout full history for inline terminal journey"]
  unless checkout.is_a?(Hash) && checkout.dig("with", "fetch-depth").to_s == "0" &&
         checkout.dig("with", "submodules") == "recursive"
    errors << "the target PR checkout must include recursive submodules and full tag history"
  end

  pnpm = steps["Activate pnpm for inline terminal journey"]
  unless pnpm.is_a?(Hash) && pnpm["run"].to_s.include?("pnpm@12.5.1")
    errors << "the target PR lane must activate the locked pnpm version"
  end

  sdk = steps["Install Android SDK platform for inline terminal journey"]
  unless sdk.is_a?(Hash) && sdk["run"].to_s.include?("platforms;android-36") &&
         sdk["run"].to_s.include?("build-tools;36.0.0")
    errors << "the target PR lane must install Android platform 36 and build tools 36.0.0"
  end

  dependencies = steps["Install locked JS dependencies for inline terminal journey"]
  unless dependencies.is_a?(Hash) && dependencies["run"] == "pnpm install --frozen-lockfile"
    errors << "the target PR lane must install the frozen JavaScript dependency set"
  end

  agents_up = steps["Start isolated Docker agents lane for inline terminal journey"]
  unless agents_up.is_a?(Hash) && agents_up["id"] == "inline_terminal_agents" &&
         agents_up["run"] == "scripts/agents-pool.sh up 2243"
    errors << "the target PR lane must start the isolated Docker agents fixture on port 2243"
  end

  journey = steps["Run packaged inline terminal journey on API 35"]
  journey_with = journey.is_a?(Hash) ? journey["with"] : nil
  journey_script = journey_with.is_a?(Hash) ? journey_with["script"].to_s : ""
  journey_env = journey.is_a?(Hash) ? journey["env"] : nil
  unless journey.is_a?(Hash) && journey["uses"] == EMULATOR_ACTION &&
         journey_with.is_a?(Hash) && journey_with["api-level"].to_s == "35" &&
         journey_with["target"] == "google_apis" &&
         journey_script.include?("scripts/connected-js-composer-docker.sh") &&
         journey_script.include?("--port 2243") &&
         journey_script.include?("--session-prefix \"$COMPOSER_SESSION_PREFIX\"")
    errors << "the target PR lane must run connected-js-composer-docker.sh on the pinned API 35 emulator against port 2243"
  end
  unless journey_env.is_a?(Hash) && journey_env["TMPDIR"] == "${{ runner.temp }}" &&
         journey_env["COMPOSER_SESSION_PREFIX"].to_s.include?("${{ github.run_id }}") &&
         journey_env["COMPOSER_SESSION_PREFIX"].to_s.include?("${{ github.run_attempt }}")
    errors << "the target PR lane must use a run-unique artifact session prefix under runner.temp"
  end
  journey_index = gate_steps.index(journey)
  if rollup_index.nil? || journey_index.nil? || rollup_index >= journey_index
    errors << "the inline journey must run after the Unit tests result rollup inside unit-gate"
  end

  artifact = named_step(gate, "Upload inline terminal journey artifacts")
  artifact_paths = artifact.dig("with", "path").to_s if artifact.is_a?(Hash)
  artifact_if = artifact.is_a?(Hash) ? artifact["if"].to_s : ""
  unless artifact.is_a?(Hash) && artifact_if.start_with?("always()") &&
         artifact_if.include?(BASE_CONDITION) &&
         artifact["uses"].to_s.start_with?("actions/upload-artifact@") &&
         artifact_paths.to_s.include?("androidTest-results/connected/debug") &&
         artifact_paths.to_s.include?("pocketshell-js2857-")
    errors << "the target PR lane must upload connected-test and extracted host artifacts even after failure"
  end

  teardown = named_step(gate, "Stop isolated Docker agents lane for inline terminal journey")
  teardown_if = teardown.is_a?(Hash) ? teardown["if"].to_s : ""
  unless teardown.is_a?(Hash) && teardown_if.start_with?("always()") &&
         teardown_if.include?(BASE_CONDITION) &&
         teardown_if.include?("steps.inline_terminal_agents.conclusion != 'skipped'") &&
         teardown["run"] == "scripts/agents-pool.sh down 2243"
    errors << "the target PR lane must tear down port 2243 with an always-run cleanup step"
  end
  agents_up_index = gate_steps.index(agents_up)
  artifact_index = gate_steps.index(artifact)
  teardown_index = gate_steps.index(teardown)
  if agents_up_index.nil? || journey_index.nil? || artifact_index.nil? || teardown_index.nil? ||
     !(agents_up_index < journey_index && journey_index < artifact_index && artifact_index < teardown_index)
    errors << "the Docker fixture must start before the journey, with artifact upload and teardown after it"
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

def self_test(workflow)
  errors = check_workflow(workflow)
  unless errors.empty?
    abort "FAIL: baseline workflow is invalid:\n#{errors.join("\n")}"
  end
  puts "PASS: self-test accepts the real workflow"

  cases = 0
  mutations = [
    ["missing base-branch trigger", /pull_request branches/, lambda { |w|
      w.fetch("on").fetch("pull_request")["branches"].delete(BASE_BRANCH)
    }],
    ["journey moved out of the required Unit tests job", /inside the existing unit-gate/, lambda { |w|
      w.fetch("jobs").fetch("unit-gate")["name"] = "Optional journey"
    }],
    ["workflow wiring guard removed from blocking static checks", /run its self-test and real check/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("guards-static"), "Inline terminal Docker journey workflow wiring")
      step["run"] = "echo no guard"
    }],
    ["missing Docker fixture startup", /start the isolated Docker agents fixture/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Start isolated Docker agents lane for inline terminal journey")
      step["run"] = "echo no fixture"
    }],
    ["journey not running API 35", /pinned API 35 emulator/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Run packaged inline terminal journey on API 35")
      step.fetch("with")["api-level"] = 34
    }],
    ["mounted journey invocation removed", /connected-js-composer-docker\.sh/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Run packaged inline terminal journey on API 35")
      step.fetch("with")["script"] = "echo no test"
    }],
    ["failure artifacts not uploaded", /upload connected-test and extracted host artifacts/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Upload inline terminal journey artifacts")
      step["if"] = BASE_CONDITION
    }],
    ["failure artifacts not scoped to target PR", /upload connected-test and extracted host artifacts/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Upload inline terminal journey artifacts")
      step["if"] = "always()"
    }],
    ["Docker fixture teardown removed", /tear down port 2243/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Stop isolated Docker agents lane for inline terminal journey")
      step["run"] = "echo no cleanup"
    }],
    ["Docker fixture teardown not scoped to target PR", /tear down port 2243/, lambda { |w|
      step = named_step(w.fetch("jobs").fetch("unit-gate"), "Stop isolated Docker agents lane for inline terminal journey")
      step["if"] = "always() && steps.inline_terminal_agents.conclusion != 'skipped'"
    }],
  ]

  mutations.each do |label, pattern, mutate|
    candidate = deep_copy(workflow)
    mutate.call(candidate)
    abort "FAIL: self-test #{label.inspect} unexpectedly passed." unless expect_red(label, candidate, pattern)
    cases += 1
  end

  puts "PASS: workflow wiring self-test (#{cases} red mutations)"
end

begin
  workflow = load_workflow(WORKFLOW_PATH)
  if ARGV == ["--self-test"]
    self_test(workflow)
  elsif ARGV.empty?
    errors = check_workflow(workflow)
    abort "FAIL: inline-terminal journey CI wiring:\n#{errors.map { |error| "  - #{error}" }.join("\n")}" unless errors.empty?
    puts "PASS: inline-terminal Docker/API35 journey is gated by the required Unit tests check for #{BASE_BRANCH}"
  else
    abort "Usage: ruby scripts/check-inline-terminal-journey-wiring.rb [--self-test]"
  end
rescue Psych::Exception => error
  abort "FAIL: could not parse #{WORKFLOW_PATH}: #{error.message.lines.first.to_s.strip}"
end
