package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Bash allowlist must not let allowlisted tools run programs or write files without a prompt. */
class BashClassifierTest {
    private fun risk(command: String) = classifyToolRisk("Bash", command)

    @Test
    fun plainReadOnlyCommandsStayReviewable() {
        assertEquals(RiskLevel.REVIEW, risk("ls -la"))
        assertEquals(RiskLevel.REVIEW, risk("rg foo src"))
        assertEquals(RiskLevel.REVIEW, risk("sort names.txt"))
    }

    @Test
    fun pathQualifiedOrPrefixedCommandsAreNotTreatedAsTheAllowlistedTool() {
        // A program the agent wrote (./ls, /workspace/cat) must not pass as `ls` or `cat`.
        assertEquals(RiskLevel.HIGH, risk("./ls"))
        assertEquals(RiskLevel.HIGH, risk("/workspace/cat file"))
        assertEquals(RiskLevel.HIGH, risk("LD_PRELOAD=/workspace/x.so cat f"))
        assertEquals(RiskLevel.HIGH, risk("PATH=/workspace/bin ls"))
        assertEquals(RiskLevel.HIGH, risk("env LD_PRELOAD=/workspace/x.so ls"))
        // System directories are still fine.
        assertEquals(RiskLevel.REVIEW, risk("/usr/bin/ls -la"))
    }

    @Test
    fun writesToTheHomeDirectoryAndCredentialStoresAreHigh() {
        assertEquals(RiskLevel.HIGH, risk("cp x ~/.bashrc"))
        assertEquals(RiskLevel.HIGH, risk("uniq in /etc/passwd"))
        assertEquals(RiskLevel.HIGH, risk("sort -oout.txt in.txt"))
        assertEquals(RiskLevel.HIGH, risk("date -s 2020-01-01"))
        assertEquals(RiskLevel.HIGH, risk("cat ~/.config/gh/hosts.yml"))
        assertEquals(RiskLevel.HIGH, risk("cat ~/.ssh/id_ed25519"))
        assertEquals(RiskLevel.HIGH, risk("sort -uo out.txt in.txt"))
        assertEquals(RiskLevel.HIGH, risk("sort --out=/etc/x in.txt"))
        assertEquals(RiskLevel.HIGH, risk("date -us 2020-01-01"))
        assertEquals(RiskLevel.HIGH, risk("sed -Ei s/a/b/ /etc/foo"))
        assertEquals(RiskLevel.HIGH, risk("/usr/bin/../../workspace/p/cat f"))
        assertEquals(RiskLevel.HIGH, risk("env -u cat sh -c x"))
        assertEquals(RiskLevel.HIGH, risk("xargs -I cat sh -c x"))
        assertEquals(RiskLevel.REVIEW, risk("sed -n 1,5p file.txt"))
        assertEquals(RiskLevel.HIGH, risk("cat /pr?c/self/environ"))
        assertEquals(RiskLevel.HIGH, risk("cat /pro''c/self/environ"))
        assertEquals(RiskLevel.HIGH, risk("cat ~/.cl*/.credentials.json"))
        assertEquals(RiskLevel.REVIEW, risk("cat src/*.kt"))
        assertEquals(RiskLevel.REVIEW, risk("ls src/?ain"))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Write", null, listOf(".git/hooks/pre-commit")))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Write", null, listOf("/workspace/p/.git/config")))
    }

    @Test
    fun allowlistedToolsWithProgramOrWriteFlagsAreHigh() {
        assertEquals(RiskLevel.HIGH, risk("rg --pre ./x.sh foo"))
        assertEquals(RiskLevel.HIGH, risk("sort --compress-program=./x.sh f"))
        assertEquals(RiskLevel.HIGH, risk("sort -o out.txt in.txt"))
        assertEquals(RiskLevel.HIGH, risk("find . -fprint out.txt"))
        assertEquals(RiskLevel.HIGH, risk("tree -o out.txt"))
        assertEquals(RiskLevel.HIGH, risk("git diff --output=out.patch"))
    }

    @Test
    fun agentHomesAndTokenFilesAreCredentialPaths() {
        assertEquals(RiskLevel.HIGH, risk("cat ~/.dsh/cordis.patch.yml"))
        assertEquals(RiskLevel.HIGH, risk("cat /root/.gemini/oauth_creds.json"))
        assertEquals(RiskLevel.HIGH, risk("cat ~/.npmrc"))
        assertEquals(RiskLevel.HIGH, risk("cat ~/.netrc"))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Read", null, listOf("/root/.dsh/cordis.patch.yml")))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Read", null, listOf("/root/.gemini/settings.json")))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Read", null, listOf("/root/.npmrc")))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Read", null, listOf("/root/.netrc")))
        assertEquals(RiskLevel.HIGH, classifyToolRisk("Read", null, listOf("/root/.config/gh/hosts.yml")))
    }

    @Test
    fun targetDirectoryOptionsOutsideWorkspaceAreHigh() {
        assertEquals(RiskLevel.HIGH, risk("cp a.txt --target-directory=/etc/cron.d"))
        assertEquals(RiskLevel.HIGH, risk("mv a.txt -t/etc/cron.d"))
        assertEquals(RiskLevel.HIGH, risk("cp a.txt -t /etc/cron.d"))
        assertEquals(RiskLevel.HIGH, risk("cp a.txt --target-directory=~/bin"))
        assertEquals(RiskLevel.REVIEW, risk("cp a.txt --target-directory=/workspace/p/out"))
    }
}
