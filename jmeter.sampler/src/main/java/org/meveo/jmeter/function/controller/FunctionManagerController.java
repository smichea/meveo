/*
 * (C) Copyright 2018-2019 Webdrone SAS (https://www.webdrone.fr/) and contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. This program is
 * not suitable for any direct or indirect application in MILITARY industry See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.meveo.jmeter.function.controller;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.Loader;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.meveo.api.dto.function.FunctionDto;
import org.meveo.jmeter.function.FunctionManager;
import org.meveo.jmeter.function.gui.functionmanager.FunctionManagerDialog;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FunctionManagerController extends MouseAdapter implements ActionListener {

    private final FunctionManagerDialog dialog;

    public FunctionManagerController(FunctionManagerDialog dialog) {
        this.dialog = dialog;

        dialog.setListItemClicked(this);
        dialog.setRefreshBtnClicked(this);
    }

    public void show(){
        populateList();
        dialog.pack();
        dialog.setVisible(true);
    }

    @Override
    public void mouseClicked(MouseEvent evt) {
        JList list = (JList) evt.getSource();
        int index = list.locationToIndex(evt.getPoint());
        final String functionCode = (String) list.getModel().getElementAt(index);
        final FunctionDto functionDto = FunctionManager.getFunctions().stream()
                .filter(e -> e.getCode().equals(functionCode))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Can't find function " + functionCode));

        try {
            if (functionDto.getTestSuite() != null) {
                PrintWriter writer = new PrintWriter("tempTreeFile.jmx", "UTF-8");
                writer.print(functionDto.getTestSuite());
                writer.close();
            } else {
                // TestPlan
                TestPlan testPlan = new TestPlan();
                testPlan.setName(functionCode);
                testPlan.setEnabled(true);
                testPlan.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
                testPlan.setProperty(TestElement.GUI_CLASS, TestPlanGui.class.getName());
                testPlan.setComment("");
                testPlan.setFunctionalMode(false);
                testPlan.setTearDownOnShutdown(true);
                testPlan.setSerialized(false);
                testPlan.setUserDefinedVariables(new Arguments());
                testPlan.setTestPlanClasspath("");

                // Create TestPlan hash tree
                HashTree testPlanHashTree = new ListedHashTree();
                testPlanHashTree.add(testPlan);

                SaveService.saveTree(testPlanHashTree, new FileOutputStream("tempTreeFile.jmx"));
            }
            ActionEvent loadEvent = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.EDIT);
            Loader.load(loadEvent, new File("tempTreeFile.jmx").getAbsoluteFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        dialog.setVisible(false);
    }

    public void populateList() {
        CompletableFuture.runAsync(() -> {
            dialog.disableDialog();
            final List<String> functionsCodes = FunctionManager.getFunctions().stream()
                    .map(FunctionDto::getCode)
                    .sorted(Collator.getInstance())
                    .sorted(Comparator.comparingInt(String::length))
                    .collect(Collectors.toList());

            dialog.populateList(functionsCodes);
            dialog.enableDialog();
            dialog.repaint();
            dialog.revalidate();
            dialog.validate();
        });

    }

    @Override
    public void actionPerformed(ActionEvent e) {
        FunctionManager.refresh();
        populateList();
    }

}
