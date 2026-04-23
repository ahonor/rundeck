# Building Rundeck OSS from Source

**Audience:** Developers building Rundeck OSS for the first time, or returning after environment changes.

This guide documents the actual build process including pitfalls discovered through trial and error. The official Rundeck build docs are sparse; this fills the gaps.

---

## Prerequisites

### Java 17

Rundeck requires Java 17. Java 8 fails at Gradle configuration time (`org.ajoberstar.grgit` requires JVM 11+). Java 21+ may work but is untested.

```bash
# macOS with Homebrew
brew install openjdk@17

# Set for this session
export JAVA_HOME=/usr/local/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

# Verify
java -version  # should show 17.x
```

**Every Gradle and Java command in this guide assumes these exports are set.** If you get cryptic build failures, check `java -version` first.

### Gradle

Rundeck ships a Gradle wrapper (`./gradlew`). Do not use a system Gradle — the wrapper pins the correct version.

---

## Quick build (WAR only, skip tests)

```bash
./gradlew rundeckapp:bootWar -x test
```

Output: `rundeckapp/build/libs/rundeck-6.0.0-SNAPSHOT.war` (~265MB)
Time: ~2 minutes on a modern Mac.

This builds the WAR without running the test suite. For development iteration, this is the command you'll use most.

---

## Full build (all modules)

```bash
./gradlew build -x test
```

Builds all modules including plugins. Takes longer but ensures everything compiles.

---

## Running the WAR

```bash
java -jar rundeckapp/build/libs/rundeck-6.0.0-SNAPSHOT.war
```

- Starts on port **4440** (configured in `rundeckapp/build/libs/etc/framework.properties` after first boot)
- First boot takes ~90 seconds (Liquibase migrations, plugin extraction, key generation)
- Subsequent boots take ~60 seconds
- Admin login: `admin` / `admin`
- RDECK_BASE: `rundeckapp/build/libs/`
- H2 database: `rundeckapp/build/libs/server/data/grailsdb.mv.db`

The H2 database and configuration are generated on first boot. **Do not delete them** — they persist your projects, jobs, and executions across restarts.

---

## npm / UI build issues

The Rundeck UI is a Vue.js SPA under `rundeckapp/grails-spa/`. The Gradle build compiles it automatically via the `:rundeckapp:copyCompiledAssets` task chain. However, the npm dependency resolution has several known issues.

### Issue 1: Private registry URLs in lockfiles

Some `package-lock.json` files contain hardcoded URLs pointing to a private Artifactory registry (PagerDuty's internal npm mirror). These fail for OSS contributors who don't have access.

**Symptom:**
```
npm ERR! 404 Not Found - GET https://artifactory.pagerduty.com/...
```

**Fix:** Rewrite the lockfile URLs to the public registry:
```bash
find rundeckapp/grails-spa -name "package-lock.json" -exec \
  sed -i '' 's|https://artifactory.pagerduty.com/artifactory/api/npm/npm-virtual/|https://registry.npmjs.org/|g' {} \;
```

### Issue 2: CLOUDSMITH_NPM_TOKEN

Some `.npmrc` files reference a `CLOUDSMITH_NPM_TOKEN` environment variable for a scoped registry.

**Symptom:**
```
npm ERR! 401 Unauthorized
```

**Fix:** Either set the token (if you have access) or remove/rename the `.npmrc` files:
```bash
find rundeckapp/grails-spa -name ".npmrc" -exec mv {} {}.bak \;
```

Then run `npm install` in the affected package directory to regenerate a clean lockfile.

### Issue 3: moduleResolution and @primeuix/themes

The `@primeuix/themes/lara` package uses the `exports` field with wildcard subpath patterns. TypeScript's `"moduleResolution": "node"` can't resolve these.

**Symptom:**
```
TS2307: Cannot find module '@primeuix/themes/lara' or its corresponding type declarations
```

**Fix:** Change `moduleResolution` from `"node"` to `"bundler"` in all three tsconfig files:
```bash
for f in tsconfig.json tsconfig.build.json tsconfig.app.json; do
  find rundeckapp/grails-spa -name "$f" -exec \
    sed -i '' 's/"moduleResolution": "node"/"moduleResolution": "bundler"/g' {} \;
done
```

### Issue 4: npm install failures after lockfile changes

After fixing registry URLs or removing `.npmrc`, you may need to clean and reinstall:
```bash
cd rundeckapp/grails-spa/packages/ui-trellis
rm -rf node_modules
npm install
```

---

## Plugin builds

Plugins under `plugins/` are built as part of the main build. They produce JAR files that are placed in `rundeckapp/build/libs/libext/` at WAR extraction time.

### Building a single plugin

```bash
./gradlew :plugins:confirm-plugin:clean :plugins:confirm-plugin:build -x test
```

The JAR goes to `plugins/confirm-plugin/build/libs/`. To deploy it to a running instance:
```bash
cp plugins/confirm-plugin/build/libs/rundeck-confirm-plugin-6.0.0-SNAPSHOT.jar \
   rundeckapp/build/libs/libext/
```

Then restart Rundeck (plugins are loaded at startup).

### External plugin builds

External plugins (not in the `plugins/` directory) that depend on `rundeck-core` need it in Maven local:
```bash
./gradlew publishToMavenLocal -x test
```

This publishes all modules (~190 tasks) to `~/.m2/repository/org/rundeck/`. The external plugin's `build.gradle` should include `mavenLocal()` in its repositories.

---

## Publishing to Maven local

Required when an external project (like a plugin in a separate repo) depends on your local Rundeck build:

```bash
./gradlew publishToMavenLocal -x test
```

Takes ~90 seconds. Publishes all modules including `rundeck-core`, `rundeck-authz-*`, `rundeck-data-models`, etc. to `~/.m2/repository/`.

---

## Common build failures

| Symptom | Cause | Fix |
|---|---|---|
| `Dependency requires at least JVM runtime version 11` | Using Java 8 | Switch to Java 17 |
| `404 Not Found` on npm packages | Private registry URLs in lockfiles | Rewrite URLs with sed (see above) |
| `401 Unauthorized` on npm | Missing `CLOUDSMITH_NPM_TOKEN` | Remove `.npmrc` or set the token |
| `TS2307: Cannot find module '@primeuix/themes/lara'` | Wrong `moduleResolution` | Change to `"bundler"` in tsconfigs |
| `Could not find org.rundeck:rundeck-core:6.0.0-SNAPSHOT` | External plugin can't resolve local build | Run `publishToMavenLocal` |
| Plugin changes not taking effect | Stale JAR in libext | Use `:clean` before `:build`, copy JAR, restart |
| Plugin JAR missing resources (JS/CSS) | Gradle up-to-date check missed changes | Use `:clean` before `:build` |
| `CompileGroovy FAILED` with no error details | Usually a missing import or type | Run the specific `compileGroovy` task to see full output |

---

## Key directories

| Path | Purpose |
|---|---|
| `core/` | rundeck-core Java library (engine, execution, plugins API) |
| `rundeckapp/` | Grails application (controllers, services, views, domain) |
| `rundeckapp/grails-spa/` | Vue.js SPA (activity list, job editor, etc.) |
| `plugins/` | Built-in plugins (confirm, flow-control, git, etc.) |
| `rundeck-authz/` | Authorization framework |
| `rundeckapp/build/libs/` | WAR output + runtime RDECK_BASE after first boot |
| `rundeckapp/build/libs/libext/` | Runtime plugin JARs |
| `rundeckapp/build/libs/server/logs/` | Server logs |
| `rundeckapp/build/libs/server/data/` | H2 database |

---

## Tips

- **Skip tests** (`-x test`) during development iteration. The full test suite takes a long time.
- **Don't delete the H2 database** between restarts. It preserves your projects, jobs, and history.
- **Check the WAR build before blaming your code.** If `bootWar` succeeds but the feature doesn't work, the plugin JAR might be stale in libext.
- **Hard-refresh the browser** (Cmd+Shift+R) after changing plugin JS/CSS. Rundeck serves plugin resources with caching headers.
- **Use `--stacktrace`** on Gradle commands to see full error output when builds fail cryptically.
