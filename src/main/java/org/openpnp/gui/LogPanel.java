package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.gui.support.AutoScroller;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.LogEntryListCellRenderer;
import org.openpnp.gui.support.LogEntryListModel;
import org.openpnp.logging.SystemLogger;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Level;
import org.pmw.tinylog.LogEntry;

public class LogPanel extends JPanel {

    private Preferences prefs = Preferences.userNodeForPackage(LogPanel.class);

    private static final String PREF_LOG_LEVEL = "LogPanel.logLevel";
    private static final String PREF_LOG_LEVEL_DEF = Level.INFO.toString();

    private boolean systemOutEnabled = true;

    private LogEntryListModel logEntries = new LogEntryListModel();
    private JList<LogEntry> logEntryJList = new JList<>(logEntries);

    private Level filterLogLevel = Level.TRACE;
    private LogEntryListModel.LogEntryFilter logLevelFilter = new LogEntryListModel.LogEntryFilter();
    private LogEntryListModel.LogEntryFilter searchBarFilter = new LogEntryListModel.LogEntryFilter();
    private LogEntryListModel.LogEntryFilter systemOutFilter = new LogEntryListModel.LogEntryFilter();

    private ScheduledExecutorService scheduledExecutor;

    public LogPanel() {

        loadLoggingPreferences();

        logEntries.addFilter(logLevelFilter);
        logEntries.addFilter(searchBarFilter);
        logEntries.addFilter(systemOutFilter);

        setLayout(new BorderLayout(0, 0));

        JScrollPane scrollPane = new JScrollPane(logEntryJList);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane, BorderLayout.CENTER);

        JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
        AutoScroller autoScroller = new AutoScroller(scrollPane);
        verticalBar.addAdjustmentListener(autoScroller);

        JPanel settingsAndFilterPanel = new JPanel();
        settingsAndFilterPanel.setLayout(new BorderLayout(0, 0));
        add(settingsAndFilterPanel, BorderLayout.NORTH);

        // The Global Filter settings
        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        settingsPanel.setBorder(new TitledBorder(null,
                "Global Logging Settings", TitledBorder.LEADING, TitledBorder.TOP, null));

        settingsPanel.add(createGlobalLogLevelPanel());

        settingsAndFilterPanel.add(settingsPanel, BorderLayout.NORTH);

        // The filter settings
        JPanel filterPanel = new JPanel(new BorderLayout(0, 0));

        filterPanel.setBorder(new TitledBorder(null,
                "Filter Logging Panel", TitledBorder.LEADING, TitledBorder.TOP, null));

        JPanel filterContentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        filterContentPanel.add(createSearchFieldPanel());
        filterContentPanel.add(createFilterLogLevelPanel());

        filterContentPanel.add(createSystemOutputCheckbox());

        filterPanel.add(filterContentPanel, BorderLayout.WEST);

        JPanel filterControlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton btnClear = new JButton(Icons.delete);
        btnClear.setToolTipText("Clear log");

        btnClear.addActionListener(e -> logEntries.clear());

        filterControlPanel.add(btnClear);

        JButton btnCopyToClipboard = new JButton(Icons.copy);
        btnCopyToClipboard.setToolTipText("Copy to clipboard");

        btnCopyToClipboard.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            logEntries.getFilteredLogEntries().forEach(logEntry -> sb.append(logEntry.getRenderedLogEntry()));
            copyStringToClipboard(sb.toString());
        });

        filterControlPanel.add(btnCopyToClipboard);

        JButton btnScroll = new JButton(Icons.scrollDown);
        btnScroll.setToolTipText("Scroll down");

        btnScroll.addActionListener(e -> {
            autoScroller.scrollDown();
        });

        filterControlPanel.add(btnScroll);

        filterPanel.add(filterControlPanel, BorderLayout.CENTER);

        settingsAndFilterPanel.add(filterPanel);

        // Log Panel
        logEntryJList.setCellRenderer(new LogEntryListCellRenderer());

        logEntryJList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()), "log-copy");
        logEntryJList.getActionMap().put("log-copy", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                ArrayList<LogEntry> logList = (ArrayList<LogEntry>) logEntryJList.getSelectedValuesList();
                StringBuilder sb = new StringBuilder();
                logList.forEach(logEntry -> sb.append(logEntry.getRenderedLogEntry()));
                copyStringToClipboard(sb.toString());
            }
        });

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                refreshLogIfOnTop();
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        MainFrame.get().getTabs().addChangeListener(new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent e) {
                refreshLogIfOnTop();
            }
        });
    }

    private JCheckBox createSystemOutputCheckbox() {
        JCheckBox systemOutCheckbox = new JCheckBox("System Output");
        systemOutCheckbox.setSelected(systemOutEnabled);
        systemOutCheckbox.addActionListener(e -> {
            systemOutEnabled = systemOutCheckbox.isSelected();
            if (systemOutEnabled) {
                systemOutFilter.setFilter(logEntry -> true);
            } else {
                systemOutFilter.setFilter(logEntry -> !Objects.equals(logEntry.getClassName(), SystemLogger.class.getName()));
            }
            logEntries.filter();
        });
        return systemOutCheckbox;
    }

    private void loadLoggingPreferences() {

        // This weird check is here because I mistakenly reused the same config key when
        // switching from slf to tinylog. This meant that some users had an int based
        // value in the key rather than the string. This caused initialization failures.
        Level level = null;
        try {
            level = Level.valueOf(prefs.get(PREF_LOG_LEVEL, PREF_LOG_LEVEL_DEF));
        } catch (Exception ignored) {
        }
        if (level == null) {
            level = Level.INFO;
        }

        Configurator
                .currentConfig()
                .level(level)
                .activate();

        Configurator
                .currentConfig()
                .addWriter(logEntries)
                .activate();
    }

    private JPanel createSearchFieldPanel() {
        JPanel searchField = new JPanel();

        JLabel lblSearch = new JLabel("Search");
        searchField.add(lblSearch);

        JTextField searchTextField = new JTextField();
        searchTextField.getDocument().addDocumentListener(new DocumentListener() {

            private void updateSearchBarFilter() {
                LogEntry entry = getSelectedEntry();
                String searchText = searchTextField.getText();
                searchBarFilter.setFilter((logEntry -> logEntry.getRenderedLogEntry().toLowerCase().contains(searchText.toLowerCase())));
                logEntries.filter();
                setSelectedEntry(entry);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSearchBarFilter();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSearchBarFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSearchBarFilter();
            }
        });

        searchTextField.setColumns(20);
        searchField.add(searchTextField);
        return searchField;
    }

    private JPanel createFilterLogLevelPanel() {
        JPanel filterLogLevelPanel = new JPanel();
        filterLogLevelPanel.add(new JLabel("Log Level:"));
        JComboBox logLevelFilterComboBox = new JComboBox(Level.values());
        logLevelFilterComboBox.setSelectedItem(filterLogLevel);
        logLevelFilterComboBox.addActionListener(e -> {
            LogEntry entry = getSelectedEntry();
            Level logLevel = (Level) logLevelFilterComboBox.getSelectedItem();
            logLevelFilter.setFilter(logEntry -> logEntry.getLevel().compareTo(logLevel) >= 0);
            logEntries.filter();
            setSelectedEntry(entry);
        });
        filterLogLevelPanel.add(logLevelFilterComboBox);
        return filterLogLevelPanel;
    }

    private JPanel createGlobalLogLevelPanel() {
        JPanel globalLogLevelPanel = new JPanel();
        globalLogLevelPanel.add(new JLabel("Global Log Level:"));
        JComboBox logLevelFilterComboBox = new JComboBox(Level.values());
        logLevelFilterComboBox.setSelectedItem((Level.valueOf(prefs.get(PREF_LOG_LEVEL, PREF_LOG_LEVEL_DEF))));
        logLevelFilterComboBox.addActionListener(e -> {
            Level logLevel = (Level) logLevelFilterComboBox.getSelectedItem();
            prefs.put(PREF_LOG_LEVEL, logLevel.toString());
            Configurator
                    .currentConfig()
                    .level(logLevel)
                    .activate();
        });
        globalLogLevelPanel.add(logLevelFilterComboBox);
        return globalLogLevelPanel;
    }

    private void copyStringToClipboard(String s) {
        StringSelection selection = new StringSelection(s);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, selection);
    }

    protected void refreshLogIfOnTop() {
        if (MainFrame.get().getTabs().getSelectedComponent() == LogPanel.this) {
            if (logEntries.isRefreshNeeded()) {
                logEntries.refresh();
            }
        }
    }

    protected LogEntry getSelectedEntry() {
        int index = logEntryJList.getSelectedIndex();
        if (index >= 0) {
            return logEntries.getElementAt(index);
        }
        return null;
    }

    protected void setSelectedEntry(LogEntry entry) {
        if (entry != null) {
            int index = logEntries.getFilteredLogEntries().indexOf(entry);
            if (index >= 0) {
                logEntryJList.setSelectedIndex(index);
            }
        }
    }

}
