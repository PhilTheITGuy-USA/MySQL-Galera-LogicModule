// Shared by tests/harness.groovy (recorded server reads) and tests/live.groovy (a real
// cluster): runs an assembled script from dist/scripts/ the way a Collector does, and keeps
// the PASS / FAILED tally. On the classpath through `groovy -cp tests` (docker-compose.yml).

import org.codehaus.groovy.runtime.InvokerHelper

// The Collector's hostProps is a ParamMap: get / set, no put, not a java.util.Map.
class Props {
    private final Map values = [:]
    Props(Map initial) { values.putAll(initial) }
    def get(String key) { values[key] }
    void set(String key, value) { values[key] = value }
}

class GaleraTestSupport {
    final File dist
    int failures = 0
    // Each script compiled once; every run gets a fresh instance and its own binding.
    private final Map<String, Class> compiled = [:]

    GaleraTestSupport(File root) { dist = new File(root, 'dist') }

    void note(String name, boolean ok, String detail = '') {
        println((ok ? 'PASS    ' : 'FAILED  ') + name + (ok || !detail ? '' : '\n        ' + detail.replace('\n', '\n        ')))
        if (!ok) { failures++ }
    }

    // Run one assembled script with these resource properties. fetch, when given, is bound as
    // galeraTestFetch: port -> [status, vars] instead of a JDBC connection.
    Map run(String script, Map props, Closure fetch = null) {
        def out = new StringWriter()
        def err = new ByteArrayOutputStream()
        def binding = new Binding()
        binding.setVariable('hostProps', new Props(props))
        binding.setVariable('out', new PrintWriter(out, true))
        if (fetch != null) { binding.setVariable('galeraTestFetch', fetch) }
        Class type = compiled.computeIfAbsent(script) { new GroovyShell().parse(new File(dist, 'scripts/' + it)).class }
        def savedErr = System.err
        System.setErr(new PrintStream(err, true))
        def exit
        try {
            exit = InvokerHelper.createScript(type, binding).run()
        } finally {
            System.setErr(savedErr)
        }
        [exit: exit, out: out.toString().readLines().findAll { it.trim() }, err: err.toString()]
    }

    // One instance's BatchScript output as [Datapoint: value].
    static Map values(Map result, String port = '3306') {
        result.out.findAll { it.startsWith(port + '.') }
            .collectEntries { def kv = it.substring(port.length() + 1).split('=', 2); [(kv[0]): kv[1]] }
    }

    int finish() {
        println(failures ? "\n${failures} FAILED" : '\nALL PASSED')
        failures ? 1 : 0
    }
}
