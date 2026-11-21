package dev.specunfold.mcp;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Packaged Windows entry: an explicit project installer, or a quiet stdio server. */
public final class DesktopLauncher {
    private DesktopLauncher() { }

    static Path applicationHome() throws Exception {
        Path jar = Paths.get(DesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return jar.getParent().getParent().getParent().toAbsolutePath().normalize();
    }

    static Path bundledNode(Path home) {
        return home.resolve("runtime-node/node.exe");
    }

    static String connectionConfig(Path executable) {
        String command = executable.toAbsolutePath().toString().replace('\\', '/');
        return "{\n  \"mcpServers\": {\n    \"specunfold\": {\n      \"command\": \""
                + command.replace("\"", "\\\"") + "\",\n      \"args\": []\n    }\n  }\n}";
    }

    public static void main(String[] args) throws Exception {
        Path home = applicationHome();
        System.setProperty("specunfold.home", home.toString());
        if (Files.isRegularFile(bundledNode(home))) {
            System.setProperty("specunfold.script.node", bundledNode(home).toString());
        }
        if (args.length == 1 && "serve".equals(args[0])) {
            SpecUnfoldServer.main(new String[0]);
            return;
        }
        if (args.length > 0 && "install".equals(args[0])) {
            String[] command = new String[args.length + 2];
            command[0] = bundledNode(home).toString();
            command[1] = home.resolve("bin/specunfold.js").toString();
            System.arraycopy(args, 0, command, 2, args.length);
            Process child = new ProcessBuilder(Arrays.asList(command)).inheritIO().start();
            System.exit(child.waitFor());
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Expected no arguments, serve, or install --workspace PATH --profile ID");
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeLater(() -> showInstaller(home));
    }

    private static void showInstaller(final Path home) {
        final JFrame frame = new JFrame("SpecUnfold · 项目接入");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(10, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        content.add(new JLabel("<html>选择需要使用 SpecUnfold 的项目目录。<br>点击安装后，会添加示例规范和助手入口，更新该项目的 AGENTS.md、CLAUDE.md。<br>已有不同内容的规范文件会阻止安装；保留入口区块外的原有指令。</html>"), BorderLayout.NORTH);
        final JTextField workspace = new JTextField(36);
        JButton browse = new JButton("选择项目目录…");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择要接入的项目目录");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                workspace.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel selection = new JPanel(new BorderLayout(8, 8));
        selection.add(workspace, BorderLayout.CENTER);
        selection.add(browse, BorderLayout.EAST);
        final JTextArea output = new JTextArea(15, 65);
        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        final Path executable = home.getParent().resolve("SpecUnfold-server.exe");
        output.setText("先安装项目规范，再把下面的连接配置填入支持 MCP（模型上下文协议）的助手或配置管理工具。\n此配置只连接服务；项目目录由助手工作区或调用参数确定。\n\n" + connectionConfig(executable));
        JPanel center = new JPanel(new BorderLayout(8, 12));
        center.add(selection, BorderLayout.NORTH);
        center.add(new JScrollPane(output), BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);
        final JButton install = new JButton("安装到所选项目");
        install.addActionListener(event -> {
            final Path target;
            try {
                if (workspace.getText().trim().isEmpty()) throw new IllegalArgumentException("请先选择项目目录。");
                target = Paths.get(workspace.getText().trim()).toAbsolutePath().normalize();
                if (!Files.isDirectory(target)) throw new IllegalArgumentException("请选择一个已存在的项目目录。");
                Path appRoot = home.getParent().toRealPath();
                if (target.toRealPath().startsWith(appRoot)) throw new IllegalArgumentException("项目目录不能位于 SpecUnfold 软件安装目录内。");
            } catch (Exception error) {
                JOptionPane.showMessageDialog(frame, error.getMessage(), "无法安装", JOptionPane.WARNING_MESSAGE);
                return;
            }
            install.setEnabled(false);
            output.setText("正在向项目安装规范与助手入口…\n" + target);
            new SwingWorker<String, Void>() {
                protected String doInBackground() throws Exception {
                    Process process = new ProcessBuilder(bundledNode(home).toString(),
                            home.resolve("bin/specunfold.js").toString(), "install", "--workspace", target.toString(),
                            "--profile", "example").redirectErrorStream(true).start();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream input = process.getInputStream()) {
                        byte[] bytes = new byte[4096];
                        int count;
                        while ((count = input.read(bytes)) != -1) buffer.write(bytes, 0, count);
                    }
                    String result = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                    if (process.waitFor() != 0) throw new IllegalStateException(result);
                    return "安装完成。项目目录：\n" + target + "\n\n请在助手或配置管理工具中添加以下服务配置：\n\n" + connectionConfig(executable);
                }

                protected void done() {
                    install.setEnabled(true);
                    try { output.setText(get()); }
                    catch (Exception error) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        output.setText("安装未完成：\n" + cause.getMessage());
                    }
                }
            }.execute();
        });
        JButton copy = new JButton("复制连接配置");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(connectionConfig(executable)), null));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(copy);
        actions.add(install);
        content.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
