/*
 * Copyright (C) 2022 <mark@makr.zone>
 * inspired and based on work
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.solutions;

import java.util.List;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.CameraPanel;
import org.openpnp.gui.components.CameraView;
import org.openpnp.gui.components.CameraView.RenderingQuality;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.camera.AbstractBroadcastingCamera;
import org.openpnp.machine.reference.camera.AbstractSettlingCamera.SettleMethod;
import org.openpnp.machine.reference.camera.OpenPnpCaptureCamera;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Solutions;
import org.openpnp.model.Solutions.Milestone;
import org.openpnp.model.Solutions.Severity;
import org.openpnp.model.Solutions.State;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

public class CameraSolutions implements Solutions.Subject  {
    ReferenceCamera camera;

    public CameraSolutions(ReferenceCamera camera) {
        this.camera = camera;
    }

    @Override
    public void findIssues(Solutions solutions) {
        if (solutions.isTargeting(Milestone.Basics)) {
            ActuatorSolutions.findActuateIssues(solutions, camera, camera.getLightActuator(), "camera light",
                "https://github.com/openpnp/openpnp/wiki/Setup-and-Calibration%3A-Camera-Lighting");
        }
        if (solutions.isTargeting(Milestone.Vision)) {
            final double previewFps = camera.getPreviewFps();
            if (previewFps > 15) {
                solutions.add(new Solutions.Issue(
                        camera, 
                        "A high Preview FPS value might create undue CPU load.", 
                        "Set to 5 FPS.", 
                        Severity.Suggestion,
                        "https://github.com/openpnp/openpnp/wiki/Setup-and-Calibration:-General-Camera-Setup#general-configuration") {


                    @Override
                    public void setState(Solutions.State state) throws Exception {
                        camera.setPreviewFps((state == Solutions.State.Solved) ? 5.0 : previewFps);
                        super.setState(state);
                    }
                });
            }
            if (! camera.isSuspendPreviewInTasks()) {
                solutions.add(new Solutions.Issue(
                        camera, 
                        "It is recommended to suspend camera preview during machine tasks / Jobs.", 
                        "Enable Suspend during tasks.", 
                        Severity.Suggestion,
                        "https://github.com/openpnp/openpnp/wiki/Setup-and-Calibration:-General-Camera-Setup#general-configuration") {

                    @Override
                    public void setState(Solutions.State state) throws Exception {
                        camera.setSuspendPreviewInTasks((state == Solutions.State.Solved));
                        super.setState(state);
                    }
                });
            }
            if (! camera.isAutoVisible()) {
                solutions.add(new Solutions.Issue(
                        camera, 
                        "In single camera preview OpenPnP can automatically switch the camera for you.", 
                        "Enable Auto Camera View.", 
                        Severity.Suggestion,
                        "https://github.com/openpnp/openpnp/wiki/Setup-and-Calibration:-General-Camera-Setup#general-configuration") {

                    @Override
                    public void setState(Solutions.State state) throws Exception {
                        camera.setAutoVisible((state == Solutions.State.Solved));
                        super.setState(state);
                    }
                });
            }
            CameraPanel cameraPanel = MainFrame.get().getCameraViews();
            CameraView view = cameraPanel.getCameraView(camera);
            if (view != null) {
                final RenderingQuality renderingQuality = view.getRenderingQuality();
                if (renderingQuality.ordinal() < RenderingQuality.High.ordinal()) {
                    solutions.add(new Solutions.Issue(
                            camera, 
                            "The preview rendering quality can be improved.", 
                            "Set to Rendering Quality to High (right click the Camera View to see other options).", 
                            Severity.Suggestion,
                            "https://github.com/openpnp/openpnp/wiki/Setup-and-Calibration:-General-Camera-Setup#camera-view-configuration") {

                        @Override
                        public void setState(Solutions.State state) throws Exception {
                            view.setRenderingQuality((state == Solutions.State.Solved) ? RenderingQuality.High : renderingQuality);
                            camera.cameraViewHasChanged(null);
                            super.setState(state);
                        }
                    });
                }
            }
            Machine machine = Configuration.get().getMachine();
            if (machine instanceof ReferenceMachine 
                    && (camera.getHead() == null || camera.getHead() instanceof ReferenceHead)) {
                VisionSolutions visionSolutions = ((ReferenceMachine)machine).getVisionSolutions();
                ReferenceHead head = (ReferenceHead) camera.getHead();
                if ((head == null || visionSolutions.isSolvedPrimaryXY(head)) 
                        && camera.getUnitsPerPixelPrimary().isInitialized()) {
                    HeadMountable movable0;
                    Location location0;
                    if (camera.getHead() == null) {
                        try {
                            movable0 = machine.getDefaultHead().getDefaultNozzle();
                            location0 = camera.getLocation(movable0);
                        }
                        catch (Exception e) {
                            movable0 = null;
                            location0 = null;
                        }
                    }
                    else {
                        movable0 = camera;
                        location0 = head.getCalibrationPrimaryFiducialLocation();
                    }
                    final HeadMountable movable = movable0;
                    final Location location = location0;
                    final SettleMethod oldSettleMethod = camera.getSettleMethod();
                    if (oldSettleMethod == SettleMethod.FixedTime) {
                        solutions.add(new Solutions.Issue(
                                camera, 
                                "Use an adaptive camera settling method.", 
                                "Set a suitable camera settling method automatically.", 
                                Severity.Fundamental,
                                "https://github.com/openpnp/openpnp/wiki/Camera-Settling") {

                            @Override 
                            public void activate() throws Exception {
                                MainFrame.get().getMachineControls().setSelectedTool(camera);
                                MovableUtils.fireTargetedUserAction(camera);
                            }

                            @Override 
                            public String getExtendedDescription() {
                                return "<html>"
                                        + "<p>For precision in computer vision it is very important that the camera image has settled down "
                                        + "after the machine has moved the camera or the subject. Some cameras exhibit a slight lag, where the "
                                        + "frames might still show the camera in motion. Motion might also cause vibration that must "
                                        + "abate before computer vision is performed.</p><br/>"
                                        + "<p>The simple solution is just to wait for a fixed amount of time. However, this is wasteful if there "
                                        + "was no motion in the first place. Using an adaptive settling method can reduce the wait time in these "
                                        + "benign cases, i.e. one does not need to set a large worst case settle wait time.</p><br/>"
                                        + (movable == camera ? 
                                                "<p><strong color=\"red\">CAUTION</strong>: The camera "+camera.getName()+" will move over the primary fiducial "
                                                + "and then perform a camera settling test pattern.</p><br/>"
                                                : (movable != null ? 
                                                        "<p><strong color=\"red\">CAUTION</strong>: The "+movable.getClass().getSimpleName()+" "+movable.getName()
                                                        +" will move to the camera "+camera.getName()+" "+ "and then perform a camera settling test pattern.</p><br/>"
                                                        : ""))
                                        + "<p>When ready, press Accept.</p>"
                                        + (getState() == State.Solved  ? 
                                                "<br/><h4>Results</h4>"
                                                + "<table>"
                                                + "<tr><td>Selected method:</td><td>"+camera.getSettleMethod()+"</td></tr>"
                                                + "<tr><td>Compute time:</td><td>"+camera.getRecordedComputeMilliseconds()+" ms per frame</td></tr>"
                                                + "</table>"
                                                : "")
                                        + "</html>";
                            }
                            @Override
                            public void setState(Solutions.State state) throws Exception {
                                if (state == State.Solved) {
                                    final State oldState = getState();
                                    UiUtils.submitUiMachineTask(
                                            () -> {
                                                calibrateCameraSettling(this, (ReferenceMachine) machine, movable, location);
                                                return true;
                                            },
                                            (result) -> {
                                                UiUtils.messageBoxOnException(() -> super.setState(state));
                                            },
                                            (t) -> {
                                                UiUtils.showError(t);
                                                // restore old state
                                                UiUtils.messageBoxOnException(() -> setState(oldState));
                                            });
                                }
                                else {
                                    camera.setSettleMethod(oldSettleMethod);
                                    super.setState(state);
                                }
                            }
                        });
                    }
                }
            }
        }
    }

    public void calibrateCameraSettling(Solutions.Issue issue, ReferenceMachine machine, HeadMountable movable, Location location) throws Exception {
        VisionSolutions visionSolutions = machine.getVisionSolutions();
        camera.setSettleMethod(SettleMethod.Motion);
        camera.setSettleMaskCircle(0);
        camera.setSettleDebounce(2);
        camera.setSettleFullColor(false);
        camera.setSettleGradients(false);
        camera.setSettleContrastEnhance(0);
        camera.setSettleThreshold(1);
        double wantedPixelRes = visionSolutions.getSettleWantedResolutionMm()
                /camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters).getX();
        camera.setSettleGaussianBlur(((int)(wantedPixelRes*5))|1);
        camera.setSettleTimeoutMs(visionSolutions.getZeroKnowledgeSettleTimeMs());
        camera.setSettleDiagnostics(true);
        // Try this.
        performSettleTest(machine, movable, location, visionSolutions.getSettleTestMoveMm());
        if (camera.getRecordedComputeMilliseconds() > visionSolutions.getSettleAcceptableComputeTime()) {
            camera.setSettleMethod(SettleMethod.Maximum);
            camera.setSettleMaskCircle(Math.sqrt(2));
            camera.setSettleThreshold(visionSolutions.getSettleMaximumPixelDiff());
            // Try this.
            performSettleTest(machine, movable, location, visionSolutions.getSettleTestMoveMm());
        }
    }

    private void performSettleTest(Machine machine, HeadMountable movable, Location location, double settleMoveMm)
            throws Exception, InterruptedException {
        if (movable != null) {
            Location location0 = location.add(new Location(LengthUnit.Millimeters, settleMoveMm*0.5, settleMoveMm*0.5, 0, 0)); 
            Location location1 = location.add(new Location(LengthUnit.Millimeters, -settleMoveMm*0.5, -settleMoveMm*0.5, 0, 0)); 
            MovableUtils.moveToLocationAtSafeZ(movable, location);
            machine.getMotionPlanner().waitForCompletion(movable, CompletionType.WaitForStillstand);
            Thread.sleep(1000);
            movable.moveTo(location0);
            movable.moveTo(location1);
        }
        camera.lightSettleAndCapture();
        Logger.debug("Settle test with method "+camera.getSettleMethod()+" has compute time "+camera.getRecordedComputeMilliseconds()+"ms.");
        if (movable != null) {
            movable.moveTo(location);
        }
    }

    /**
     * Create a replacement OpenPnpCaptureCamera for this camera with some of the
     * generic settings transferred.  
     * 
     * @return
     */
    public static OpenPnpCaptureCamera createReplacementCamera(ReferenceCamera oldCamera) {
        OpenPnpCaptureCamera camera = new OpenPnpCaptureCamera();
        camera.setHead(oldCamera.getHead());
        camera.setId(oldCamera.getId());
        camera.setLooking(oldCamera.getLooking());
        camera.setName(oldCamera.getName());
        camera.setHeadOffsets(oldCamera.getHeadOffsets());
        camera.setAxisX(oldCamera.getAxisX());
        camera.setAxisY(oldCamera.getAxisY());
        camera.setAxisZ(oldCamera.getAxisZ());
        camera.setAxisRotation(oldCamera.getAxisRotation());
        camera.setPreviewFps(oldCamera.getPreviewFps());
        camera.setSuspendPreviewInTasks(oldCamera.isSuspendPreviewInTasks());
        camera.setAutoVisible(oldCamera.isAutoVisible());
        camera.setLightActuator(oldCamera.getLightActuator());
        camera.setAllowMachineActuators(oldCamera.isAllowMachineActuators());
        camera.setBeforeCaptureLightOn(oldCamera.isBeforeCaptureLightOn());
        camera.setAfterCaptureLightOff(oldCamera.isAfterCaptureLightOff());
        camera.setUserActionLightOn(oldCamera.isUserActionLightOn());
        camera.setAntiGlareLightOff(oldCamera.isAntiGlareLightOff());
        return camera;
    }

    /**
     * Replace a camera with the same Id at the same place in the cameras list.
     * 
     * @param camera
     * @throws Exception
     */
    public static void replaceCamera(Camera camera) throws Exception {
        // Disable the machine, so the driver isn't connected.
        Machine machine = Configuration.get().getMachine();
        // Find the old driver with the same Id.
        Head cameraHead = camera.getHead();
        List<Camera> list = (cameraHead == null ? machine.getCameras() : cameraHead.getCameras());
        Camera replaced = null;
        int index;
        for (index = 0; index < list.size(); index++) {
            if (list.get(index).getId().equals(camera.getId())) {
                replaced = list.get(index);
                if (cameraHead == null) {
                    machine.removeCamera(replaced);
                }
                else {
                    cameraHead.removeCamera(replaced);
                }
                MainFrame.get().getCameraViews().removeCamera(replaced);
                if (replaced instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) replaced).close();
                    }
                    catch (Exception e) {
                        Logger.warn(e);
                    }
                }
                break;
            }
        }
        final int formerIndex = index;

        UiUtils.messageBoxOnExceptionLater(() -> {
            // Add the new one.
            if (cameraHead == null) {
                machine.addCamera(camera);
            }
            else {
                cameraHead.addCamera(camera);
            }
            // Permutate it back to the old list place (cumbersome but works).
            for (int p = list.size() - formerIndex; p > 1; p--) {
                if (cameraHead == null) {
                    machine.permutateCamera(camera, -1);
                }
                else {
                    cameraHead.permutateCamera(camera, -1);
                }
            }
            if (camera instanceof AbstractBroadcastingCamera) {
                ((AbstractBroadcastingCamera) camera).reinitialize();
            }
            MainFrame.get().getCameraViews().addCamera(camera);
        });
    }
}
