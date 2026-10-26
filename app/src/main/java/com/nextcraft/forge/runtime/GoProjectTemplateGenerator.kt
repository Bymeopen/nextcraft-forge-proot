package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.projectSlug
import java.io.File

/**
 * Starter for a Go command-line app. It builds natively in the chroot (arm64) and cross-compiles to Windows and Linux
 * with the Build matrix targets that [detectCrossTargets] finds from `go.mod`.
 */
internal object GoProjectTemplateGenerator {
    /** Ubuntu 24.04's `golang-go` is 1.22; the `go` line must not ask for more than the chroot has. */
    const val GO_VERSION = "1.22"

    /** Relative path to file content for a project called [appName]. */
    fun files(appName: String): Map<String, String> {
        val module = projectSlug(appName)
        return linkedMapOf(
            "go.mod" to "module $module\n\ngo $GO_VERSION\n",
            "main.go" to mainGo(module),
            "main_test.go" to mainTestGo(),
            ".gitignore" to "dist/\n",
            "README.md" to readme(appName, module),
        )
    }

    /** Writes the starter into [destination]; refuses to touch a folder that already has a `go.mod`. */
    fun generate(destination: File, appName: String) {
        check(!File(destination, "go.mod").exists()) { "The folder already contains a Go module" }
        destination.mkdirs()
        files(appName).forEach { (path, content) -> File(destination, path).writeText(content) }
    }

    private fun mainGo(module: String) = """
        package main

        import (
        	"flag"
        	"fmt"
        	"runtime"
        )

        const appName = "$module"

        // version is set at build time: go build -ldflags "-X main.version=1.2.3".
        var version = "dev"

        func greeting(name string) string {
        	return fmt.Sprintf("Hello, %s!", name)
        }

        func main() {
        	name := flag.String("name", "world", "who to greet")
        	showVersion := flag.Bool("version", false, "print the version and exit")
        	flag.Parse()

        	if *showVersion {
        		fmt.Printf("%s %s (%s/%s)\n", appName, version, runtime.GOOS, runtime.GOARCH)
        		return
        	}
        	fmt.Println(greeting(*name))
        }
    """.trimIndent() + "\n"

    private fun mainTestGo() = """
        package main

        import "testing"

        func TestGreeting(t *testing.T) {
        	if got := greeting("Forge"); got != "Hello, Forge!" {
        		t.Fatalf("greeting = %q", got)
        	}
        }
    """.trimIndent() + "\n"

    private fun readme(appName: String, module: String) = """
        # $appName

        A Go command-line app. Needs `golang-go` in the chroot (`apt-get install -y golang-go`).

        ```sh
        go test ./...
        go run . -name Forge
        ```

        ## Build for other systems

        The Build matrix lists these targets; the commands are the same by hand. Output goes to `dist/`.

        ```sh
        GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o dist/windows-amd64/ ./...   # $module.exe
        GOOS=linux   GOARCH=amd64 CGO_ENABLED=0 go build -o dist/linux-amd64/ ./...
        GOOS=linux   GOARCH=arm64 CGO_ENABLED=0 go build -o dist/linux-arm64/ ./...
        ```

        The Windows and Linux x86_64 files cannot run on the phone; copy them to a computer to try them.
    """.trimIndent() + "\n"
}
