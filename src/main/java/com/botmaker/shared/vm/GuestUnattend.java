package com.botmaker.shared.vm;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code autounattend.xml} that installs Windows in a game VM with nobody at the keyboard. Windows Setup
 * reads it from the root of any disc ({@link IsoImage} writes that disc).
 * <ul>
 *   <li><b>windowsPE:</b> skips Windows 11's TPM, Secure Boot, RAM and CPU checks ({@code LabConfig}),
 *       wipes the first disk into EFI, MSR and Windows partitions, and installs the Pro edition with
 *       Microsoft's generic installation key, which installs and does not activate.</li>
 *   <li><b>specialize:</b> names the computer, and lets setup finish without a network or Microsoft account.</li>
 *   <li><b>oobeSystem:</b> a local administrator {@code user} with {@code password} who signs in by
 *       themselves, and no account, privacy or region pages.</li>
 *   <li><b>first sign-in:</b> no sleep, screen timeout or lock screen; the hypervisor's guest tools, from
 *       whichever disc has them; the {@value #LAUNCH_TASK} task, which runs {@value #LAUNCH_SCRIPT} on the
 *       user's desktop when the host starts it; and {@value #READY_FILE} last, so the host can tell setup is
 *       over.</li>
 * </ul>
 * The password is in the file as plain text, which is how Windows Setup reads it; the disc is the VM's own
 * and is removed once setup is done.
 */
public final class GuestUnattend {

    /** The guest's folder for what BotMaker puts there. */
    public static final String GUEST_FOLDER = "C:\\BotMaker";
    /** What the {@value #LAUNCH_TASK} task runs; the host writes the game's command into it. */
    public static final String LAUNCH_SCRIPT = GUEST_FOLDER + "\\launch.cmd";
    /** Written before the launch task starts, and deleted by {@value #LAUNCH_SCRIPT} once it ran its command. */
    public static final String LAUNCH_PENDING = GUEST_FOLDER + "\\launch.pending";
    /** Written by the last first-sign-in command. */
    public static final String READY_FILE = GUEST_FOLDER + "\\ready";
    /** A scheduled task that runs {@value #LAUNCH_SCRIPT} in the signed-in user's session when started. */
    public static final String LAUNCH_TASK = "BotMaker launch";
    /** Microsoft's generic key for Windows 11 Pro: it chooses the edition, and does not activate. */
    static final String PRO_INSTALL_KEY = "VK7JG-NPHTM-C97JM-9MPGT-3V66T";

    private static final String COMPONENT = "<component name=\"%s\" processorArchitecture=\"amd64\""
            + " publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\">";

    private GuestUnattend() {}

    /**
     * The answer file.
     *
     * @param language the Windows disc's language ({@code en-US}, {@code fr-FR}, …): a different one makes
     *                 Setup stop and ask
     */
    public static String xml(String user, String password, String computerName, String language,
                             Hypervisor hypervisor) {
        if (!user.matches("[A-Za-z][A-Za-z0-9_-]{0,19}")) throw new IllegalArgumentException("Not a Windows user name: " + user);
        if (!computerName.matches("[A-Za-z0-9-]{1,15}")) {
            throw new IllegalArgumentException("Not a Windows computer name: " + computerName);
        }
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<unattend xmlns=\"urn:schemas-microsoft-com:unattend\"")
                .append(" xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\">\n");

        x.append("<settings pass=\"windowsPE\">\n");
        x.append(component("Microsoft-Windows-International-Core-WinPE"))
                .append("<SetupUILanguage><UILanguage>").append(esc(language)).append("</UILanguage></SetupUILanguage>\n")
                .append(locales(language))
                .append("</component>\n");
        x.append(component("Microsoft-Windows-Setup"));
        x.append("<RunSynchronous>\n");
        int order = 1;
        for (String check : List.of("BypassTPMCheck", "BypassSecureBootCheck", "BypassRAMCheck", "BypassCPUCheck",
                "BypassStorageCheck")) {
            x.append(runSynchronous(order++, "reg add HKLM\\SYSTEM\\Setup\\LabConfig /v " + check
                    + " /t REG_DWORD /d 1 /f"));
        }
        x.append("</RunSynchronous>\n");
        x.append("""
                <DiskConfiguration>
                <Disk wcm:action="add">
                <DiskID>0</DiskID>
                <WillWipeDisk>true</WillWipeDisk>
                <CreatePartitions>
                <CreatePartition wcm:action="add"><Order>1</Order><Type>EFI</Type><Size>300</Size></CreatePartition>
                <CreatePartition wcm:action="add"><Order>2</Order><Type>MSR</Type><Size>16</Size></CreatePartition>
                <CreatePartition wcm:action="add"><Order>3</Order><Type>Primary</Type><Extend>true</Extend></CreatePartition>
                </CreatePartitions>
                <ModifyPartitions>
                <ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>1</PartitionID><Format>FAT32</Format><Label>System</Label></ModifyPartition>
                <ModifyPartition wcm:action="add"><Order>2</Order><PartitionID>3</PartitionID><Format>NTFS</Format><Label>Windows</Label><Letter>C</Letter></ModifyPartition>
                </ModifyPartitions>
                </Disk>
                </DiskConfiguration>
                <ImageInstall><OSImage><InstallTo><DiskID>0</DiskID><PartitionID>3</PartitionID></InstallTo></OSImage></ImageInstall>
                """);
        x.append("<UserData><AcceptEula>true</AcceptEula><ProductKey><Key>").append(PRO_INSTALL_KEY)
                .append("</Key><WillShowUI>OnError</WillShowUI></ProductKey></UserData>\n");
        x.append("</component>\n</settings>\n");

        x.append("<settings pass=\"specialize\">\n");
        x.append(component("Microsoft-Windows-Shell-Setup"))
                .append("<ComputerName>").append(esc(computerName)).append("</ComputerName>\n")
                .append("</component>\n");
        x.append(component("Microsoft-Windows-Deployment")).append("<RunSynchronous>\n")
                .append(runSynchronous(1, "reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE"
                        + " /v BypassNRO /t REG_DWORD /d 1 /f"))
                .append("</RunSynchronous>\n</component>\n");
        x.append("</settings>\n");

        x.append("<settings pass=\"oobeSystem\">\n");
        x.append(component("Microsoft-Windows-International-Core")).append(locales(language)).append("</component>\n");
        x.append(component("Microsoft-Windows-Shell-Setup"));
        x.append("""
                <OOBE>
                <HideEULAPage>true</HideEULAPage>
                <HideOEMRegistrationScreen>true</HideOEMRegistrationScreen>
                <HideOnlineAccountScreens>true</HideOnlineAccountScreens>
                <HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE>
                <ProtectYourPC>3</ProtectYourPC>
                </OOBE>
                """);
        x.append("<UserAccounts><LocalAccounts><LocalAccount wcm:action=\"add\">")
                .append("<Name>").append(esc(user)).append("</Name>")
                .append("<Group>Administrators</Group>")
                .append("<Password><Value>").append(esc(password)).append("</Value><PlainText>true</PlainText></Password>")
                .append("</LocalAccount></LocalAccounts></UserAccounts>\n");
        x.append("<AutoLogon><Enabled>true</Enabled><LogonCount>9999999</LogonCount>")
                .append("<Username>").append(esc(user)).append("</Username>")
                .append("<Password><Value>").append(esc(password)).append("</Value><PlainText>true</PlainText></Password>")
                .append("</AutoLogon>\n");
        x.append("<FirstLogonCommands>\n");
        List<String> commands = firstLogonCommands(hypervisor);
        for (int i = 0; i < commands.size(); i++) {
            x.append("<SynchronousCommand wcm:action=\"add\"><Order>").append(i + 1).append("</Order>")
                    .append("<CommandLine>").append(esc(commands.get(i))).append("</CommandLine></SynchronousCommand>\n");
        }
        x.append("</FirstLogonCommands>\n</component>\n</settings>\n");
        x.append("</unattend>\n");
        return x.toString();
    }

    /** VMware Tools' installer: silent, and no restart until Windows next restarts. */
    private static final String TOOLS_ARGS = "/S /v \"/qn REBOOT=R\"";

    /**
     * What runs at the first sign-in, as the signed-in user, in order: power, lock screen, guest tools, the
     * launch task, ready. The task names no {@code /RU}: it is that user's, and naming one would make schtasks
     * stop and ask for its password.
     */
    static List<String> firstLogonCommands(Hypervisor hypervisor) {
        List<String> c = new ArrayList<>(List.of(
                "cmd /c mkdir " + GUEST_FOLDER,
                "powercfg /change monitor-timeout-ac 0",
                "powercfg /change standby-timeout-ac 0",
                "powercfg /hibernate off",
                "reg add HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\Personalization /v NoLockScreen /t REG_DWORD /d 1 /f",
                "reg add \"HKCU\\Control Panel\\Desktop\" /v ScreenSaveActive /t REG_SZ /d 0 /f"));
        switch (hypervisor) {
            // VMware Tools, on whichever drive letter its disc got (the one with VMwareToolsUpgrader.exe: the
            // Windows disc has a setup.exe too); it reboots later. Older discs name the 64-bit installer
            // setup64.exe, current ones ship only setup.exe, 64-bit: live, setup64.exe alone installed nothing.
            case VMWARE -> c.add("cmd /c for %d in (D E F G H I) do if exist %d:\\VMwareToolsUpgrader.exe"
                    + " (if exist %d:\\setup64.exe (start /wait %d:\\setup64.exe " + TOOLS_ARGS + ")"
                    + " else start /wait %d:\\setup.exe " + TOOLS_ARGS + ")");
            // virtio-win's guest tools: the serial driver the guest agent talks through, and the agent.
            case QEMU -> c.add("cmd /c for %d in (D E F G H I) do if exist %d:\\virtio-win-guest-tools.exe"
                    + " start /wait %d:\\virtio-win-guest-tools.exe /install /quiet /norestart");
            case UNKNOWN -> { }
        }
        c.add("cmd /c echo rem the host writes the game's command here> " + LAUNCH_SCRIPT);
        c.add(createLaunchTask());
        c.add("cmd /c echo ready> " + READY_FILE);
        return List.copyOf(c);
    }

    /** The {@value #LAUNCH_TASK} task as the first sign-in creates it, for the signed-in user. */
    private static String createLaunchTask() {
        return "schtasks /Create /TN \"" + LAUNCH_TASK + "\" /TR \"" + LAUNCH_SCRIPT + "\" /SC ONCE /ST 00:00"
                + " /IT /RL HIGHEST /F";
    }

    /**
     * PowerShell, run as the guest's user, that starts the {@value #LAUNCH_TASK} task, creating it first in a VM
     * whose first sign-in didn't (one set up by hand), with {@value #LAUNCH_PENDING} written first; exits with
     * {@code schtasks}' code.
     */
    public static String launchTaskScript() {
        return "schtasks /Query /TN '" + LAUNCH_TASK + "' *> $null\n"
                + "if ($LASTEXITCODE -ne 0) { " + createLaunchTask() + " | Out-Null }\n"
                + "Set-Content -Path '" + LAUNCH_PENDING + "' -Value ''\n"
                + "schtasks /Run /TN '" + LAUNCH_TASK + "'\n"
                + "exit $LASTEXITCODE\n";
    }

    private static String locales(String language) {
        String l = esc(language);
        return "<InputLocale>" + l + "</InputLocale><SystemLocale>" + l + "</SystemLocale><UILanguage>" + l
                + "</UILanguage><UserLocale>" + l + "</UserLocale>\n";
    }

    private static String component(String name) {
        return COMPONENT.formatted(name) + "\n";
    }

    private static String runSynchronous(int order, String path) {
        return "<RunSynchronousCommand wcm:action=\"add\"><Order>" + order + "</Order><Path>" + esc(path)
                + "</Path></RunSynchronousCommand>\n";
    }

    static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
