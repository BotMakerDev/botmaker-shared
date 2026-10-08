package com.botmaker.shared.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legendary, the Epic launcher of a Linux game VM, signed in to the user's Epic account. Epic's sign-in page
 * ({@link #SIGN_IN_PAGE}) opens in this PC's browser; once the user has signed in it shows a one-time code, which
 * the user pastes back, and the VM's Legendary trades it for its own sign-in. The account's password never
 * reaches BotMaker, and the code goes into the guest as a file, never a command line, which the guest agent logs.
 */
public final class Legendary {

    /** Epic's sign-in, answering with the code Legendary signs in with ({@code authorizationCode}). */
    public static final String SIGN_IN_PAGE = "https://legendary.gl/epiclogin";
    /** Legendary's sign-in in the guest: whether it exists is all BotMaker reads of it. */
    static final String USER_FILE = LinuxGameCopy.HOME + "/.config/legendary/user.json";
    private static final Pattern CODE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern IN_ANSWER = Pattern.compile("\"authorizationCode\"\\s*:\\s*\"([0-9a-f]{32})\"");
    private static final int AGENT_TIMEOUT_MS = 30_000;

    private Legendary() {}

    /** The code in what the user pasted: the code itself, or the whole answer the page showed; empty for neither. */
    public static Optional<String> code(String pasted) {
        String text = pasted.strip();
        if (CODE.matcher(text).matches()) return Optional.of(text);
        Matcher m = IN_ANSWER.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Whether {@code vm}'s Legendary is signed in. */
    public static boolean signedIn(VmRecord vm) throws IOException {
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            return agent.fileExists(USER_FILE);
        }
    }

    /**
     * Signs {@code vm}'s Legendary in with {@code code} ({@link #code}).
     *
     * @throws IOException when Epic refused the code (it lasts a few minutes, and once), or the guest didn't answer
     */
    public static void signIn(VmRecord vm, String code) throws IOException, InterruptedException {
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("Not a sign-in code.");
        String token = VmCredentials.randomText(12);
        String file = LinuxDisplay.FOLDER + "/legendary-" + token + ".code";
        String script = LinuxDisplay.FOLDER + "/legendary-sign-in-" + token + ".sh";
        GuestAgent.Ran ran;
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            agent.run("/bin/mkdir", List.of("-p", LinuxDisplay.FOLDER), Duration.ofSeconds(30));
            agent.writeFile(script, signInScript().getBytes(StandardCharsets.UTF_8));
            agent.writeFile(file, code.getBytes(StandardCharsets.US_ASCII));
            try {
                ran = agent.run("/bin/sh", List.of(script, file), Duration.ofMinutes(2));
            } finally {
                agent.exec("/bin/rm", List.of("-f", script, file));
            }
        }
        if (ran.exitCode() != 0) {
            String[] lines = ran.output().strip().split("\\R");
            throw new IOException("Epic didn't sign the VM in: " + lines[lines.length - 1]);
        }
    }

    /**
     * {@code legendary-sign-in-TOKEN.sh FILE}, as root: reads the code from {@code FILE}, deletes it, and signs
     * the VM's user in with it, in place of any account signed in before: Legendary keeps a sign-in still valid
     * and ignores the code.
     */
    static String signInScript() {
        return "#!/bin/sh\n"
                + "code=$(cat \"$1\")\n"
                + "rm -f \"$1\"\n"
                + "/usr/sbin/runuser " + String.join(" ", asUser("auth", "--delete")) + " >/dev/null 2>&1\n"
                + "exec /usr/sbin/runuser " + String.join(" ", asUser("auth", "--code")) + " \"$code\"\n";
    }

    /** {@code runuser}'s arguments that run Legendary with {@code arguments} as the VM's user. */
    static List<String> asUser(String... arguments) {
        List<String> all = new ArrayList<>(List.of("-u", VmSetup.GUEST_USER, "--", "/usr/bin/env",
                "HOME=" + LinuxGameCopy.HOME, GuestLauncher.EPIC.linuxExecutable()));
        all.addAll(List.of(arguments));
        return all;
    }
}
