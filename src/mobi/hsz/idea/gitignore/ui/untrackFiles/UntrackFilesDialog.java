/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore.ui.untrackFiles;

import com.intellij.dvcs.repo.Repository;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashMap;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeModelAdapter;
import com.intellij.util.ui.tree.TreeUtil;
import mobi.hsz.idea.gitignore.IgnoreBundle;
import mobi.hsz.idea.gitignore.util.Utils;
import mobi.hsz.idea.gitignore.util.exec.ExternalExec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Map;

import static mobi.hsz.idea.gitignore.IgnoreManager.RefreshTrackedIgnoredListener.TRACKED_IGNORED_REFRESH;

/**
 * Dialog that lists all untracked but indexed files in a tree view, allows select specific files
 * and perform command to untrack them.
 *
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 1.7
 */
public class UntrackFilesDialog extends DialogWrapper {
    /** Current project. */
    @NotNull
    private final Project project;

    /** A list of the tracked but ignored files. */
    @NotNull
    private final HashMap<VirtualFile, Repository> files;

    /** Templates tree root node. */
    @NotNull
    private final FileTreeNode root;

    /** Map of the tree view {@link FileTreeNode} nodes. */
    @NotNull
    private final Map<VirtualFile, FileTreeNode> nodes = ContainerUtil.newHashMap();

    /** Commands editor with syntax highlight. */
    private Editor commands;

    /** {@link Document} related to the {@link Editor} feature. */
    private Document commandsDocument;

    /** Templates tree with checkbox feature. */
    private CheckboxTree tree;

    /** Tree expander responsible for expanding and collapsing tree structure. */
    private DefaultTreeExpander treeExpander;

    /** Listener that checks if files list has been changed and rewrites commands in {@link #commandsDocument}. */
    @NotNull
    private TreeModelListener treeModelListener = new TreeModelAdapter() {
        /**
         * Invoked after a tree has changed.
         *
         * @param event the event object specifying changed nodes
         * @param type  the event type specifying a kind of changes
         */
        @Override
        protected void process(TreeModelEvent event, EventType type) {
            final String text = getCommandsText();

            ApplicationManager.getApplication().runWriteAction(new Runnable() {
                @Override
                public void run() {
                    commandsDocument.setText(text);
                }
            });
        }
    };

    /**
     * Constructor.
     *
     * @param project current project
     * @param files   files map to present
     */
    public UntrackFilesDialog(@NotNull Project project, @NotNull HashMap<VirtualFile, Repository> files) {
        super(project, false);
        this.project = project;
        this.files = files;
        this.root = createDirectoryNodes(project.getBaseDir(), null);

        setTitle(IgnoreBundle.message("dialog.untrackFiles.title"));
        setOKButtonText(IgnoreBundle.message("global.ok"));
        setCancelButtonText(IgnoreBundle.message("global.cancel"));
        init();
    }

    /**
     * Builds recursively nested {@link FileTreeNode} nodes structure.
     *
     * @param file       current {@link VirtualFile} instance
     * @param repository {@link Repository} of given file
     * @return leaf
     */
    @NotNull
    private FileTreeNode createDirectoryNodes(@NotNull VirtualFile file, @Nullable Repository repository) {
        final FileTreeNode node = nodes.get(file);
        if (node != null) {
            return node;
        }

        final FileTreeNode newNode = new FileTreeNode(project, file, repository);
        nodes.put(file, newNode);

        if (nodes.size() != 1) {
            final VirtualFile parent = file.getParent();
            if (parent != null) {
                createDirectoryNodes(parent, null).add(newNode);
            }
        }

        return newNode;
    }

    /**
     * Creates center panel of {@link DialogWrapper}.
     *
     * @return panel
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        final JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setPreferredSize(new Dimension(500, 400));

        final JPanel treePanel = new JPanel(new BorderLayout());
        centerPanel.add(treePanel, BorderLayout.CENTER);

        /* Scroll panel for the templates tree. */
        JScrollPane treeScrollPanel = createTreeScrollPanel();
        treePanel.add(treeScrollPanel, BorderLayout.CENTER);

        final JPanel northPanel = new JPanel(new GridBagLayout());
        northPanel.setBorder(IdeBorderFactory.createEmptyBorder(2, 0, 2, 0));
        northPanel.add(createTreeActionsToolbarPanel(treeScrollPanel).getComponent(),
                new GridBagConstraints(0, 0, 1, 1, 1, 1, GridBagConstraints.BASELINE_LEADING,
                        GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0)
        );
        treePanel.add(northPanel, BorderLayout.NORTH);

        // Create commands preview section
        commandsDocument = EditorFactory.getInstance().createDocument(getCommandsText());
        commands = Utils.createPreviewEditor(commandsDocument, project, true);

        final JPanel commandsPanel = new JPanel(new BorderLayout());
        final JLabel commandsLabel = new JBLabel(IgnoreBundle.message("dialog.untrackFiles.commands.label"));
        commandsLabel.setBorder(IdeBorderFactory.createEmptyBorder(10, 0, 10, 0));
        commandsPanel.add(commandsLabel, BorderLayout.NORTH);

        JComponent commandsComponent = commands.getComponent();
        commandsComponent.setPreferredSize(new Dimension(0, 200));
        commandsPanel.add(commandsComponent, BorderLayout.CENTER);
        centerPanel.add(commandsPanel, BorderLayout.SOUTH);

        return centerPanel;
    }

    /**
     * Creates scroll panel with templates tree in it.
     *
     * @return scroll panel
     */
    private JScrollPane createTreeScrollPanel() {
        for (Map.Entry<VirtualFile, Repository> entry : files.entrySet()) {
            createDirectoryNodes(entry.getKey(), entry.getValue());
        }

        final FileTreeRenderer renderer = new FileTreeRenderer();

        tree = new CheckboxTree(renderer, root);
        tree.setCellRenderer(renderer);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(false);
        UIUtil.setLineStyleAngled(tree);
        TreeUtil.installActions(tree);

        final JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(tree);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        TreeUtil.expandAll(tree);

        tree.getModel().addTreeModelListener(treeModelListener);
        treeExpander = new DefaultTreeExpander(tree);

        return scrollPane;
    }

    /**
     * Creates tree toolbar panel with actions for working with templates tree.
     *
     * @param target templates tree
     * @return action toolbar
     */
    private ActionToolbar createTreeActionsToolbarPanel(@NotNull JComponent target) {
        final CommonActionsManager actionManager = CommonActionsManager.getInstance();
        final DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(actionManager.createExpandAllAction(treeExpander, tree));
        actions.add(actionManager.createCollapseAllAction(treeExpander, tree));

        final ActionToolbar actionToolbar = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.UNKNOWN, actions, true);
        actionToolbar.setTargetComponent(target);

        return actionToolbar;
    }

    /**
     * This method is invoked by default implementation of "OK" action. It just closes dialog with
     * <code>OK_EXIT_CODE</code>. This is convenient place to override functionality of "OK" action.
     * Note that the method does nothing if "OK" action isn't enabled.
     */
    @Override
    protected void doOKAction() {
        super.doOKAction();

        HashMap<Repository, ArrayList<VirtualFile>> checked = getCheckedFiles();
        for (Map.Entry<Repository, ArrayList<VirtualFile>> entry : checked.entrySet()) {
            for (VirtualFile file : entry.getValue()) {
                ExternalExec.removeFileFromTracking(file, entry.getKey());
            }
        }

        project.getMessageBus().syncPublisher(TRACKED_IGNORED_REFRESH).refresh();
    }

    /**
     * Returns structured map of selected {@link VirtualFile} list sorted by {@link Repository}.
     *
     * @return sorted files map
     */
    @NotNull
    private HashMap<Repository, ArrayList<VirtualFile>> getCheckedFiles() {
        final HashMap<Repository, ArrayList<VirtualFile>> result = new HashMap<Repository, ArrayList<VirtualFile>>();

        FileTreeNode leaf = (FileTreeNode) root.getFirstLeaf();
        if (leaf == null) {
            return result;
        }

        do {
            if (!leaf.isChecked()) {
                continue;
            }

            final Repository repository = leaf.getRepository();
            final VirtualFile file = leaf.getFile();
            if (repository == null) {
                continue;
            }

            ArrayList<VirtualFile> list = ContainerUtil.getOrCreate(result, repository, new ArrayList<VirtualFile>());
            list.add(file);

            result.put(repository, list);
        } while ((leaf = (FileTreeNode) leaf.getNextLeaf()) != null);

        return result;
    }

    /**
     * Returns ready to present commands list.
     *
     * @return commands list
     */
    @NotNull
    private String getCommandsText() {
        final StringBuilder builder = new StringBuilder();

        HashMap<Repository, ArrayList<VirtualFile>> checked = getCheckedFiles();
        for (Map.Entry<Repository, ArrayList<VirtualFile>> entry : checked.entrySet()) {
            VirtualFile repository = entry.getKey().getRoot();
            builder.append(IgnoreBundle.message(
                    "dialog.untrackFiles.commands.repository",
                    repository.getCanonicalPath()
            )).append("\n");

            for (VirtualFile file : entry.getValue()) {
                builder.append(IgnoreBundle.message(
                        "dialog.untrackFiles.commands.command",
                        Utils.getRelativePath(repository, file)
                )).append("\n");
            }

            builder.append("\n");
        }
        return builder.toString();
    }
}
