package cz.ok1xoe.cqww;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CqwwLogDownloaderGuiApp extends Application {

    private TextArea output;
    private Button startBtn;
    private Button stopBtn;

    private Process currentProcess;

    public static void launchApp(String[] args) {
        Application.launch(CqwwLogDownloaderGuiApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("CQWW Log Downloader (GUI)");

        TabPane tabs = new TabPane();
        tabs.getTabs().add(buildBulkTab(stage));
        tabs.getTabs().add(buildTargetedTab(stage));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        output = new TextArea();
        output.setEditable(false);
        output.setWrapText(false);
        output.setPrefRowCount(18);

        startBtn = new Button("Start");
        stopBtn = new Button("Stop");
        stopBtn.setDisable(true);

        stopBtn.setOnAction(e -> stopCurrentProcess());

        HBox buttons = new HBox(10, startBtn, stopBtn);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(10, tabs, new Label("Output:"), output, buttons);
        root.setPadding(new Insets(12));

        stage.setScene(new Scene(root, 900, 650));
        stage.show();
    }

    private Tab buildBulkTab(Stage stage) {
        TextField url = new TextField("https://cqww.com/publiclogs/");
        TextField outDir = new TextField(System.getProperty("user.dir"));
        TextField maxConc = new TextField("100");
        TextField retries = new TextField("3");

        ChoiceBox<String> overwrite = new ChoiceBox<>();
        overwrite.getItems().addAll("replace", "skip", "new");
        overwrite.setValue("replace");

        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select output directory");
            File initial = new File(outDir.getText());
            if (initial.isDirectory()) dc.setInitialDirectory(initial);
            File selected = dc.showDialog(stage);
            if (selected != null) outDir.setText(selected.getAbsolutePath());
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int r = 0;
        grid.add(new Label("URL:"), 0, r);
        grid.add(url, 1, r++, 2, 1);

        grid.add(new Label("Out:"), 0, r);
        grid.add(outDir, 1, r);
        grid.add(browse, 2, r++);

        grid.add(new Label("maxConcurrent:"), 0, r);
        grid.add(maxConc, 1, r++);

        grid.add(new Label("retries:"), 0, r);
        grid.add(retries, 1, r++);

        grid.add(new Label("overwrite:"), 0, r);
        grid.add(overwrite, 1, r++);

        VBox box = new VBox(10, grid);
        box.setPadding(new Insets(10));

        Tab tab = new Tab("Bulk");
        tab.setContent(box);

        startBtn.setOnAction(e -> {
            List<String> cliArgs = new ArrayList<>();
            cliArgs.add("--url=" + url.getText().trim());
            cliArgs.add("--out=" + outDir.getText().trim());
            cliArgs.add("--maxConcurrent=" + maxConc.getText().trim());
            cliArgs.add("--retries=" + retries.getText().trim());
            cliArgs.add("--overwrite=" + overwrite.getValue());
            runCli(cliArgs);
        });

        return tab;
    }

    private Tab buildTargetedTab(Stage stage) {
        TextField call = new TextField();
        TextField year = new TextField();
        ChoiceBox<String> mode = new ChoiceBox<>();
        mode.getItems().addAll("", "CW", "SSB", "RTTY");
        mode.setValue("");

        TextField outDir = new TextField(System.getProperty("user.dir"));
        TextField retries = new TextField("3");

        ChoiceBox<String> overwrite = new ChoiceBox<>();
        overwrite.getItems().addAll("replace", "skip", "new");
        overwrite.setValue("replace");

        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select output directory");
            File initial = new File(outDir.getText());
            if (initial.isDirectory()) dc.setInitialDirectory(initial);
            File selected = dc.showDialog(stage);
            if (selected != null) outDir.setText(selected.getAbsolutePath());
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int r = 0;
        grid.add(new Label("call (required):"), 0, r);
        grid.add(call, 1, r++);

        grid.add(new Label("year (optional):"), 0, r);
        grid.add(year, 1, r++);

        grid.add(new Label("mode (optional):"), 0, r);
        grid.add(mode, 1, r++);

        grid.add(new Label("Out:"), 0, r);
        grid.add(outDir, 1, r);
        grid.add(browse, 2, r++);

        grid.add(new Label("retries:"), 0, r);
        grid.add(retries, 1, r++);

        grid.add(new Label("overwrite:"), 0, r);
        grid.add(overwrite, 1, r++);

        VBox box = new VBox(10, grid);
        box.setPadding(new Insets(10));

        Tab tab = new Tab("Targeted");
        tab.setContent(box);

        startBtn.setOnAction(e -> {
            List<String> cliArgs = new ArrayList<>();

            String c = call.getText().trim();
            if (c.isBlank()) {
                appendLine("ERROR: --call is required in targeted mode.");
                return;
            }
            cliArgs.add("--call=" + c);

            String y = year.getText().trim();
            if (!y.isBlank()) cliArgs.add("--year=" + y);

            String m = mode.getValue() == null ? "" : mode.getValue().trim();
            if (!m.isBlank()) cliArgs.add("--mode=" + m);

            cliArgs.add("--out=" + outDir.getText().trim());
            cliArgs.add("--retries=" + retries.getText().trim());
            cliArgs.add("--overwrite=" + overwrite.getValue());

            runCli(cliArgs);
        });

        return tab;
    }

    private void runCli(List<String> args) {
        if (currentProcess != null && currentProcess.isAlive()) {
            appendLine("INFO: Process is already running. Stop it first.");
            return;
        }

        output.clear();

        List<String> cmd = new ArrayList<>();
        cmd.add(findJavaExecutable());
        cmd.add("-jar");
        cmd.add(findRunningJarPath());
        // DŮLEŽITÉ: nepředávat --gui, jinak by se GUI spouštělo rekurzivně
        cmd.addAll(args);

        appendLine("CMD: " + String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            currentProcess = pb.start();

            startBtn.setDisable(true);
            stopBtn.setDisable(false);

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = br.readLine()) != null) {
                        appendLine(line);
                    }
                } catch (IOException e) {
                    appendLine("ERROR: Failed to read process output: " + e.getMessage());
                } finally {
                    int code;
                    try {
                        code = currentProcess.waitFor();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        code = -1;
                    }
                    int finalCode = code;
                    Platform.runLater(() -> {
                        appendLine("EXIT: " + finalCode);
                        startBtn.setDisable(false);
                        stopBtn.setDisable(true);
                    });
                }
            }, "cqww-gui-cli-reader");

            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            appendLine("ERROR: Cannot start process: " + e.getMessage());
            startBtn.setDisable(false);
            stopBtn.setDisable(true);
        }
    }

    private void stopCurrentProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            appendLine("INFO: Stopping process...");
            currentProcess.destroy();
        }
    }

    private void appendLine(String line) {
        Platform.runLater(() -> output.appendText(line + System.lineSeparator()));
    }

    private String findJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path java = Path.of(javaHome, "bin", "java");
        return java.toString();
    }

    private String findRunningJarPath() {
        try {
            var uri = CqwwLogDownloaderApplication.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();

            Path p = Path.of(uri).toAbsolutePath().normalize();

            // Když běžíte z IDE, nebude to .jar (bude to target/classes).
            if (!p.toString().endsWith(".jar")) {
                throw new IllegalStateException("GUI launcher requires running from a packaged .jar (currently: " + p + ")");
            }
            return p.toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve running jar path.", e);
        }
    }
}
