package com.shmuelzon.HomeAssistantFloorPlan;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.InterruptedException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.media.j3d.Transform3D;
import javax.vecmath.Point2d;
import javax.vecmath.Vector2d;
import javax.vecmath.Vector4d;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Compass;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Room;


public class Controller {
    public class ProgressUpdate {
        private int completed;
        private String statusText;

        public ProgressUpdate(int completed, String statusText) {
            this.completed = completed;
            this.statusText = statusText;
        }

        public int getCompleted() {
            return completed;
        }

        public String getStatusText() {
            return statusText;
        }
    }

    public enum Property {PROGRESS_UPDATE, NUMBER_OF_RENDERS, PREVIEW_UPDATE}
    public enum Renderer {YAFARAY, SUNFLOW}
    public enum Quality {HIGH, LOW}
    public enum ImageFormat {PNG, JPEG}

    private static final String TRANSPARENT_IMAGE_NAME = "transparent";
    private static final String CEILING_LIGHT_NAME_KEYWORD = "deckenlampe";
    // Minimum amount by which green must exceed red and blue for a pixel to be
    // treated as the chroma-key background while building the stamp. Keeps the
    // detection brightness-independent so the green key is still recognised when
    // the scene is rendered dark (e.g. at a night render time).
    private static final int STAMP_BACKGROUND_GREEN_MARGIN = 16;
    // The stamp is a binary mask: INSIDE marks pixels that belong to the floor
    // plan, OUTSIDE marks the chroma-key background that gets cut away.
    private static final int STAMP_INSIDE = Color.WHITE.getRGB();
    private static final int STAMP_OUTSIDE = Color.BLACK.getRGB();

    private static final String CONTROLLER_RENDER_WIDTH = "renderWidth";
    private static final String CONTROLLER_RENDER_HEIGHT = "renderHeigh";
    private static final String CONTROLLER_RENDERER = "renderer";
    private static final String CONTROLLER_QUALITY = "quality";
    private static final String CONTROLLER_IMAGE_FORMAT = "imageFormat";
    private static final String CONTROLLER_RENDER_TIME = "renderTime";
    private static final String CONTROLLER_OUTPUT_DIRECTORY_NAME = "outputDirectoryName";
    private static final String CONTROLLER_USE_EXISTING_RENDERS = "useExistingRenders";
    private static final String CONTROLLER_ENABLE_FLOOR_PLAN_POST_PROCESSING = "enableFloorPlanPostProcessing";
    private static final String CONTROLLER_TRANSPARENCY_THRESHOLD = "transparencyThreshold";
    private static final String CONTROLLER_MAINTAIN_ASPECT_RATIO = "maintainAspectRatio";
    private static final String CONTROLLER_GENERATE_FLOORPLAN_YAML = "generateFloorplanYaml";
    private static final String CONTROLLER_CEILING_LIGHTS_INTENSITY = "ceilingLightsIntensity";
    private static final String CONTROLLER_OTHER_LIGHTS_INTENSITY = "otherLightsIntensity";
    private static final String CONTROLLER_RENDER_CEILING_LIGHTS_INTENSITY = "renderCeilingLightsIntensity";
    private static final String CONTROLLER_RENDER_OTHER_LIGHTS_INTENSITY = "renderOtherLightsIntensity";
    private static final String CONTROLLER_CREATE_ROOM_SELECTORS = "createRoomSelectors";
    private static final String CONTROLLER_CREATE_ROOM_TOP_SELECTORS = "createRoomTopSelectors";
    private static final String CONTROLLER_STAMP_SMOOTHING = "stampSmoothing";

    private Home home;
    private Settings settings;
    private Camera camera;
    private List<Entity> lightEntities = new ArrayList<>();
    private List<Entity> otherEntities = new ArrayList<>();
    private List<Entity> otherLevelsEntities = new ArrayList<>();
    private Map<String, List<Entity>> lightsGroups = new HashMap<>();
    private Vector4d cameraPosition;
    private Transform3D perspectiveTransform;
    private PropertyChangeSupport propertyChangeSupport;
    private int numberOfCompletedRenders;
    private AbstractPhotoRenderer photoRenderer;
    private int renderWidth;
    private int renderHeight;
    private Renderer renderer;
    private Quality quality;
    private ImageFormat imageFormat;
    private long renderTime;
    private List<Long> renderDateTimes;
    private String outputDirectoryName;
    private String outputRendersDirectoryName;
    private String outputFloorplanDirectoryName;
    private boolean useExistingRenders;
    private boolean enableFloorPlanPostProcessing;
    private int transparencyThreshold;
    private boolean maintainAspectRatio;
    private boolean generateFloorplanYaml;
    private int ceilingLightsIntensity;
    private int otherLightsIntensity;
    private int renderCeilingLightsIntensity;
    private int renderOtherLightsIntensity;
    private boolean createRoomSelectors;
    private boolean createRoomTopSelectors;
    private int stampSmoothing;
    private Rectangle cropArea = null;
    // Cropped (and optionally blurred) stamp coverage, computed once per render
    // and reused for every processed image since it never changes within a run.
    private float[] stampCoverage;
    private int stampCoverageWidth;
    private int stampCoverageHeight;
    private Scenes scenes;

    public Controller(Home home) {
        this.home = home;
        settings = new Settings(home);
        camera = home.getCamera().clone();
        propertyChangeSupport = new PropertyChangeSupport(this);
        loadDefaultSettings();
        createHomeAssistantEntities();

        buildLightsGroups();
        buildScenes();
        repositionEntities();
    }

    public void loadDefaultSettings() {
        renderWidth = settings.getInteger(CONTROLLER_RENDER_WIDTH, 1024);
        renderHeight = settings.getInteger(CONTROLLER_RENDER_HEIGHT, 576);
        renderer = Renderer.valueOf(settings.get(CONTROLLER_RENDERER, Renderer.YAFARAY.name()));
        quality = Quality.valueOf(settings.get(CONTROLLER_QUALITY, Quality.HIGH.name()));
        imageFormat = ImageFormat.valueOf(settings.get(CONTROLLER_IMAGE_FORMAT, ImageFormat.PNG.name()));
        // A single configured time (the night/display moment). The brightest
        // daytime moment for base_day is derived from it automatically. Reading
        // it as a list keeps backward compatibility with the previous two-time
        // format; the last entry was the night time.
        List<Long> savedRenderTimes = settings.getListLong(CONTROLLER_RENDER_TIME, Arrays.asList(camera.getTime()));
        renderTime = savedRenderTimes.get(savedRenderTimes.size() - 1);
        updateRenderDateTimes();
        outputDirectoryName = settings.get(CONTROLLER_OUTPUT_DIRECTORY_NAME, System.getProperty("user.home"));
        outputRendersDirectoryName = outputDirectoryName + File.separator + "renders";
        outputFloorplanDirectoryName = outputDirectoryName + File.separator + "floorplan";
        useExistingRenders = settings.getBoolean(CONTROLLER_USE_EXISTING_RENDERS, true);
        enableFloorPlanPostProcessing = settings.getBoolean(CONTROLLER_ENABLE_FLOOR_PLAN_POST_PROCESSING, true);
        transparencyThreshold = settings.getInteger(CONTROLLER_TRANSPARENCY_THRESHOLD, 30);
        maintainAspectRatio = settings.getBoolean(CONTROLLER_MAINTAIN_ASPECT_RATIO, true);
        generateFloorplanYaml = settings.getBoolean(CONTROLLER_GENERATE_FLOORPLAN_YAML, false);
        ceilingLightsIntensity = settings.getInteger(CONTROLLER_CEILING_LIGHTS_INTENSITY, 8);
        otherLightsIntensity = settings.getInteger(CONTROLLER_OTHER_LIGHTS_INTENSITY, 3);
        renderCeilingLightsIntensity = settings.getInteger(CONTROLLER_RENDER_CEILING_LIGHTS_INTENSITY, 20);
        renderOtherLightsIntensity = settings.getInteger(CONTROLLER_RENDER_OTHER_LIGHTS_INTENSITY, 10);
        createRoomSelectors = settings.getBoolean(CONTROLLER_CREATE_ROOM_SELECTORS, false);
        createRoomTopSelectors = settings.getBoolean(CONTROLLER_CREATE_ROOM_TOP_SELECTORS, false);
        stampSmoothing = settings.getInteger(CONTROLLER_STAMP_SMOOTHING, 0);
    }

    public void addPropertyChangeListener(Property property, PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(property.name(), listener);
    }

    public void removePropertyChangeListener(Property property, PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(property.name(), listener);
    }

    public List<Entity> getLightEntities() {
        return lightEntities;
    }

    public List<Entity> getOtherEntities() {
        return otherEntities;
    }

    public Map<String, List<Entity>> getLightsGroups() {
        return lightsGroups;
    }

    private int getNumberOfControllableLights(List<Entity> lights) {
        int numberOfControllableLights = 0;

        for (Entity light : lights)
            numberOfControllableLights += light.getAlwaysOn() ? 0 : 1;

        return numberOfControllableLights;
    }

    public int getNumberOfTotalRenders() {
        if (scenes == null)
            return 0;

        int totalRenders = 0;

        // Count stamp image
        if (enableFloorPlanPostProcessing) {
            totalRenders++;
        }

        // Count light combination renders
        int numberOfLightRenders = 1; // for base_day
        for (List<Entity> groupLights : lightsGroups.values()) {
            numberOfLightRenders += getNumberOfControllableLights(groupLights);
        }
        totalRenders += numberOfLightRenders;

        // Count night base image (always rendered alongside base_day)
        totalRenders++;

        if (generateFloorplanYaml) {
            totalRenders++;
        }

        return totalRenders;
    }

    public int getRenderHeight() {
        return renderHeight;
    }

    public void setRenderHeight(int renderHeight) {
        this.renderHeight = renderHeight;
        settings.setInteger(CONTROLLER_RENDER_HEIGHT, renderHeight);
        repositionEntities();
    }

    public int getRenderWidth() {
        return renderWidth;
    }

    public void setRenderWidth(int renderWidth) {
        this.renderWidth = renderWidth;
        settings.setInteger(CONTROLLER_RENDER_WIDTH, renderWidth);
        repositionEntities();
    }

    public String getOutputDirectory() {
        return outputDirectoryName;
    }

    public void setOutputDirectory(String outputDirectoryName) {
        this.outputDirectoryName = outputDirectoryName;
        outputRendersDirectoryName = outputDirectoryName + File.separator + "renders";
        outputFloorplanDirectoryName = outputDirectoryName + File.separator + "floorplan";
        settings.set(CONTROLLER_OUTPUT_DIRECTORY_NAME, outputDirectoryName);
    }

    public boolean getUseExistingRenders() {
        return useExistingRenders;
    }

    public void setUseExistingRenders(boolean useExistingRenders) {
        this.useExistingRenders = useExistingRenders;
        settings.setBoolean(CONTROLLER_USE_EXISTING_RENDERS, useExistingRenders);
    }

    public boolean getEnableFloorPlanPostProcessing() {
        return enableFloorPlanPostProcessing;
    }

    public void setEnableFloorPlanPostProcessing(boolean enableFloorPlanPostProcessing) {
        this.enableFloorPlanPostProcessing = enableFloorPlanPostProcessing;
        settings.setBoolean(CONTROLLER_ENABLE_FLOOR_PLAN_POST_PROCESSING, enableFloorPlanPostProcessing);
    }

    public int getTransparencyThreshold() {
        return transparencyThreshold;
    }

    public void setTransparencyThreshold(int transparencyThreshold) {
        this.transparencyThreshold = transparencyThreshold;
        settings.setInteger(CONTROLLER_TRANSPARENCY_THRESHOLD, transparencyThreshold);
    }

    public boolean getMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public void setMaintainAspectRatio(boolean maintainAspectRatio) {
        this.maintainAspectRatio = maintainAspectRatio;
        settings.setBoolean(CONTROLLER_MAINTAIN_ASPECT_RATIO, maintainAspectRatio);
    }

    public boolean getGenerateFloorplanYaml() {
        return generateFloorplanYaml;
    }

    public void setGenerateFloorplanYaml(boolean generateFloorplanYaml) {
        this.generateFloorplanYaml = generateFloorplanYaml;
        settings.setBoolean(CONTROLLER_GENERATE_FLOORPLAN_YAML, generateFloorplanYaml);
    }

    public Renderer getRenderer() {
        return renderer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
        settings.set(CONTROLLER_RENDERER, renderer.name());
    }

    public Quality getQuality() {
        return quality;
    }

    public void setQuality(Quality quality) {
        this.quality = quality;
        settings.set(CONTROLLER_QUALITY, quality.name());
    }

    public ImageFormat getImageFormat() {
        return imageFormat;
    }

    public void setImageFormat(ImageFormat imageFormat) {
        this.imageFormat = imageFormat;
        settings.set(CONTROLLER_IMAGE_FORMAT, imageFormat.name());
    }

    public List<Long> getRenderDateTimes() {
        return renderDateTimes;
    }

    public long getRenderTime() {
        return renderTime;
    }

    public void setRenderTime(long renderTime) {
        this.renderTime = renderTime;
        settings.setListLong(CONTROLLER_RENDER_TIME, Arrays.asList(renderTime));
        updateRenderDateTimes();
        buildScenes();
    }

    // The day/night pair the rest of the pipeline renders from: base_day at the
    // brightest moment of the configured day (so it is never dark even when the
    // configured time is at night), base_night and every light render at the
    // configured (night/display) time. Always two entries so the day/night
    // switching conditions in the generated YAML keep working.
    private void updateRenderDateTimes() {
        renderDateTimes = Arrays.asList(dayTimeFor(renderTime), renderTime);
    }

    private long dayTimeFor(long nightTime) {
        long oneDay = 24L * 60 * 60 * 1000;
        long startOfDay = (nightTime / oneDay) * oneDay;
        long brightest = brightestTimeOfDay(startOfDay);
        if (brightest != startOfDay)
            return brightest;
        // No compass to locate solar noon - fall back to midday of that day.
        return startOfDay + oneDay / 2;
    }

    public int getCeilingLightsIntensity() {
        return ceilingLightsIntensity;
    }

    public void setCeilingLightsIntensity(int ceilingLightsIntensity) {
        this.ceilingLightsIntensity = ceilingLightsIntensity;
        settings.setInteger(CONTROLLER_CEILING_LIGHTS_INTENSITY, ceilingLightsIntensity);
    }

    public int getOtherLightsIntensity() {
        return otherLightsIntensity;
    }

    public void setOtherLightsIntensity(int otherLightsIntensity) {
        this.otherLightsIntensity = otherLightsIntensity;
        settings.setInteger(CONTROLLER_OTHER_LIGHTS_INTENSITY, otherLightsIntensity);
    }

    public int getRenderCeilingLightsIntensity() {
        return renderCeilingLightsIntensity;
    }

    public void setRenderCeilingLightsIntensity(int renderCeilingLightsIntensity) {
        this.renderCeilingLightsIntensity = renderCeilingLightsIntensity;
        settings.setInteger(CONTROLLER_RENDER_CEILING_LIGHTS_INTENSITY, renderCeilingLightsIntensity);
    }

    public int getRenderOtherLightsIntensity() {
        return renderOtherLightsIntensity;
    }

    public void setRenderOtherLightsIntensity(int renderOtherLightsIntensity) {
        this.renderOtherLightsIntensity = renderOtherLightsIntensity;
        settings.setInteger(CONTROLLER_RENDER_OTHER_LIGHTS_INTENSITY, renderOtherLightsIntensity);
    }

    public boolean getCreateRoomSelectors() {
        return createRoomSelectors;
    }

    public void setCreateRoomSelectors(boolean createRoomSelectors) {
        this.createRoomSelectors = createRoomSelectors;
        settings.setBoolean(CONTROLLER_CREATE_ROOM_SELECTORS, createRoomSelectors);
    }

    public boolean getCreateRoomTopSelectors() {
        return createRoomTopSelectors;
    }

    public void setCreateRoomTopSelectors(boolean createRoomTopSelectors) {
        this.createRoomTopSelectors = createRoomTopSelectors;
        settings.setBoolean(CONTROLLER_CREATE_ROOM_TOP_SELECTORS, createRoomTopSelectors);
    }

    public int getStampSmoothing() {
        return stampSmoothing;
    }

    public void setStampSmoothing(int stampSmoothing) {
        this.stampSmoothing = stampSmoothing;
        settings.setInteger(CONTROLLER_STAMP_SMOOTHING, stampSmoothing);
    }

    public void stop() {
        if (photoRenderer != null) {
            photoRenderer.stop();
            photoRenderer = null;
        }
    }

    public boolean isProjectEmpty() {
        return home == null || home.getFurniture().isEmpty();
    }

    public void render() throws IOException, InterruptedException {
        numberOfCompletedRenders = 0;
        propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(numberOfCompletedRenders, "Starting render..."));
        cropArea = null;
        stampCoverage = null;
        int originalSkyColor = home.getEnvironment().getSkyColor();
        int originalGroundColor = home.getEnvironment().getGroundColor();
        BufferedImage stencilMask = null;

        try {
            Files.createDirectories(Paths.get(outputRendersDirectoryName));
            Files.createDirectories(Paths.get(outputFloorplanDirectoryName));

            if (enableFloorPlanPostProcessing) {
                propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(numberOfCompletedRenders, "Generating stamp..."));
                File stampFile = new File(outputFloorplanDirectoryName + File.separator + "stamp.png");
                if (useExistingRenders && stampFile.exists()) {
                    stencilMask = ImageIO.read(stampFile);
                } else {
                    home.getEnvironment().setSkyColor(AutoCrop.CROP_COLOR.getRGB());
                    home.getEnvironment().setGroundColor(AutoCrop.CROP_COLOR.getRGB());
                    // Render the stamp at the bright daytime moment (renderDateTimes
                    // index 0 is already the brightest time of the configured day) so
                    // the green chroma key stays as bright as possible, regardless of
                    // the night-time render moment. The stamp is only used for its
                    // silhouette, so the exact time just needs the key easy to detect.
                    camera.setTime(renderDateTimes.get(0));
                    BufferedImage tempBaseImage = renderScene();
                    stencilMask = createFloorplanStamp(tempBaseImage);
                    home.getEnvironment().setSkyColor(originalSkyColor);
                    home.getEnvironment().setGroundColor(originalGroundColor);
                }
                this.cropArea = findCropAreaFromStamp(stencilMask);
                updateEntityPositionsForCrop();
                propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(++numberOfCompletedRenders, "Stamp processed."));
            }

            generateTransparentImage(outputFloorplanDirectoryName + File.separator + TRANSPARENT_IMAGE_NAME + ".png");
            String yaml = String.format(
                "type: picture-elements\n" +
                "image: /local/floorplan/%s.png?version=%s\n" +
                "elements:\n", TRANSPARENT_IMAGE_NAME, renderHash(TRANSPARENT_IMAGE_NAME, true));

            turnOffLightsFromOtherLevels();

            camera.setTime(renderDateTimes.get(0));
            BufferedImage rawDayBaseImage = processImage("base_day", new ArrayList<>(), null, stencilMask);
            if (generateFloorplanYaml) {
                yaml += generateLightYaml(new Scene(camera, renderDateTimes, renderDateTimes.get(0), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()), Collections.emptyList(), null, "base_day", false);
            }

            // Render the dimmed night ambiance before the per-room light
            // combinations so those combinations are layered on top of it with
            // the lighten blend mode instead of being hidden behind it.
            camera.setTime(renderDateTimes.get(renderDateTimes.size() - 1));
            processImage("base_night", null, null, stencilMask);
            if (generateFloorplanYaml) {
                yaml += generateLightYaml(new Scene(camera, renderDateTimes, renderDateTimes.get(renderDateTimes.size() - 1), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()), Collections.emptyList(), null, "base_night", false);
            }

            for (String group : lightsGroups.keySet()) {
                List<Entity> groupLights = lightsGroups.get(group);
                long renderTime = renderDateTimes.get(renderDateTimes.size() - 1);
                camera.setTime(renderTime);

                List<List<Entity>> lightCombinations = getCombinations(groupLights);
                for (List<Entity> onLights : lightCombinations) {
                    String imageName = String.join("_", onLights.stream().map(Entity::getName).collect(Collectors.toList()));
                    BufferedImage lightImage = processImage(imageName, onLights, rawDayBaseImage, stencilMask);

                    Entity firstLight = onLights.get(0);
                    if (firstLight.getIsRgb()) {
                        generateRedTintedImage(lightImage, imageName, stencilMask);
                        Scene nightScene = new Scene(camera, renderDateTimes, renderTime, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                        yaml += generateRgbLightYaml(nightScene, firstLight, imageName);
                    } else {
                        Scene nightScene = new Scene(camera, renderDateTimes, renderTime, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                        yaml += generateLightYaml(nightScene, groupLights, onLights, imageName);
                    }
                }
            }

            if (generateFloorplanYaml) {
                propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(++numberOfCompletedRenders, "Generating floorplan.yaml..."));
                yaml += generateEntitiesYaml();
                Files.write(Paths.get(outputDirectoryName + File.separator + "floorplan.yaml"), yaml.getBytes());
            }

            if (createRoomSelectors) {
                generateRoomSelectorImages(stencilMask);
            }

            if (createRoomTopSelectors) {
                generateRoomTopSelectorImages(stencilMask);
            }
        } catch (InterruptedIOException | ClosedByInterruptException e) {
            throw new InterruptedException();
        } catch (IOException e) {
            throw e;
        } finally {
            home.getEnvironment().setSkyColor(originalSkyColor);
            home.getEnvironment().setGroundColor(originalGroundColor);
            restoreEntityConfiguration();
        }
    }

    private BufferedImage createFloorplanStamp(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();

        // 1. Classify every pixel: background where it shows the chroma key
        //    (sky/ground), foreground where it belongs to the floor plan.
        boolean[] background = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                background[y * width + x] = isStampBackgroundColor(image.getRGB(x, y));
            }
        }

        // 2. Flood-fill the background reachable from the image border. Only this
        //    "exterior" gets cut away; background fully enclosed by the floor plan
        //    (e.g. an interior courtyard) is kept so the silhouette stays solid.
        boolean[] exterior = findExteriorBackground(background, width, height);

        // 3. Build the binary mask: everything that is not exterior is inside.
        BufferedImage stamp = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                stamp.setRGB(x, y, exterior[y * width + x] ? STAMP_OUTSIDE : STAMP_INSIDE);
            }
        }

        File stampFile = new File(outputFloorplanDirectoryName + File.separator + "stamp.png");
        ImageIO.write(stamp, "png", stampFile);

        return stamp;
    }

    // Marks every background pixel connected to the image border via an
    // iterative flood fill. Each pixel is marked before being pushed, so it
    // enters the stack at most once - keeping memory bounded by the pixel count
    // even on large renders (the previous queue re-enqueued every neighbour).
    private boolean[] findExteriorBackground(boolean[] background, int width, int height) {
        boolean[] exterior = new boolean[width * height];
        int[] stack = new int[width * height];
        int top = 0;

        for (int x = 0; x < width; x++) {
            top = pushIfBackground(background, exterior, stack, top, x, 0, width);
            top = pushIfBackground(background, exterior, stack, top, x, height - 1, width);
        }
        for (int y = 0; y < height; y++) {
            top = pushIfBackground(background, exterior, stack, top, 0, y, width);
            top = pushIfBackground(background, exterior, stack, top, width - 1, y, width);
        }

        while (top > 0) {
            int index = stack[--top];
            int x = index % width;
            int y = index / width;
            if (x > 0)          top = pushIfBackground(background, exterior, stack, top, x - 1, y, width);
            if (x < width - 1)  top = pushIfBackground(background, exterior, stack, top, x + 1, y, width);
            if (y > 0)          top = pushIfBackground(background, exterior, stack, top, x, y - 1, width);
            if (y < height - 1) top = pushIfBackground(background, exterior, stack, top, x, y + 1, width);
        }

        return exterior;
    }

    private int pushIfBackground(boolean[] background, boolean[] exterior, int[] stack, int top, int x, int y, int width) {
        int index = y * width + x;
        if (background[index] && !exterior[index]) {
            exterior[index] = true;
            stack[top++] = index;
        }
        return top;
    }

    // Finds the moment of highest sun elevation within the 24h following the
    // given reference time, using the home's compass (location/north). Scanning
    // a full day always covers solar noon, so the result is the brightest time
    // at this location. Falls back to the reference time when no compass exists.
    private long brightestTimeOfDay(long referenceTime) {
        Compass compass = home.getCompass();
        if (compass == null)
            return referenceTime;

        long step = 15 * 60 * 1000L;       // 15 minutes
        long oneDay = 24 * 60 * 60 * 1000L;
        long brightestTime = referenceTime;
        float highestElevation = compass.getSunElevation(referenceTime);
        for (long time = referenceTime; time < referenceTime + oneDay; time += step) {
            float elevation = compass.getSunElevation(time);
            if (elevation > highestElevation) {
                highestElevation = elevation;
                brightestTime = time;
            }
        }
        return brightestTime;
    }

    private BufferedImage applyFloorplanStamp(BufferedImage image, BufferedImage stamp) {
        float[] coverage = getStampCoverage(stamp);
        int coverageWidth = stampCoverageWidth;
        int coverageHeight = stampCoverageHeight;

        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage finalImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Coverage is indexed by its own width and only covers the area
                // it was built for; anything outside that is fully transparent.
                float c = (x < coverageWidth && y < coverageHeight) ? coverage[y * coverageWidth + x] : 0f;
                if (c <= 0f) {
                    finalImage.setRGB(x, y, 0x00000000);
                    continue;
                }
                int source = image.getRGB(x, y);
                int sourceAlpha = (source >>> 24) & 0xFF;
                int alpha = Math.min(255, Math.round(sourceAlpha * c));
                finalImage.setRGB(x, y, (alpha << 24) | (source & 0x00FFFFFF));
            }
        }
        return finalImage;
    }

    // Returns the stamp coverage cropped to the render area, computing it once
    // and caching it: cropArea, smoothing and the stamp are all fixed for the
    // duration of a render, so every processed image shares the same coverage.
    private float[] getStampCoverage(BufferedImage stamp) {
        if (stampCoverage == null) {
            BufferedImage croppedStamp = new AutoCrop().crop(stamp, cropArea, maintainAspectRatio, renderWidth, renderHeight);
            stampCoverageWidth = croppedStamp.getWidth();
            stampCoverageHeight = croppedStamp.getHeight();
            stampCoverage = buildStampCoverage(croppedStamp, stampSmoothing);
        }
        return stampCoverage;
    }

    private float[] buildStampCoverage(BufferedImage stamp, int smoothing) {
        int width = stamp.getWidth();
        int height = stamp.getHeight();
        // Coverage is 1.0 inside the stamp and 0.0 outside. When smoothing is
        // enabled the binary mask is blurred so the cut-out edge fades smoothly
        // instead of staying hard/jagged.
        float[] coverage = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Anything that isn't the OUTSIDE colour belongs to the floor plan.
                coverage[y * width + x] = (stamp.getRGB(x, y) & 0x00FFFFFF) != (STAMP_OUTSIDE & 0x00FFFFFF) ? 1f : 0f;
            }
        }
        if (smoothing <= 0)
            return coverage;
        return boxBlur(coverage, width, height, smoothing);
    }

    private float[] boxBlur(float[] source, int width, int height, int radius) {
        float[] horizontal = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0f;
                int count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= width)
                        continue;
                    sum += source[y * width + xx];
                    count++;
                }
                horizontal[y * width + x] = sum / count;
            }
        }

        float[] blurred = new float[width * height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                float sum = 0f;
                int count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= height)
                        continue;
                    sum += horizontal[yy * width + x];
                    count++;
                }
                blurred[y * width + x] = sum / count;
            }
        }
        return blurred;
    }

    private Rectangle findCropAreaFromStamp(BufferedImage stamp) {
        int width = stamp.getWidth();
        int height = stamp.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Check for inside (non-OUTSIDE) pixels
                if ((stamp.getRGB(x, y) & 0x00FFFFFF) != (STAMP_OUTSIDE & 0x00FFFFFF)) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX == -1) { // Stamp is entirely outside
            return new Rectangle(0, 0, width, height);
        }

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private void addEligibleFurnitureToMap(Map<String, List<HomePieceOfFurniture>> furnitureByName, List<HomePieceOfFurniture> lightsFromOtherLevels, List<HomePieceOfFurniture> furnitureList) {
        for (HomePieceOfFurniture piece : furnitureList) {
            if (piece instanceof HomeFurnitureGroup) {
                addEligibleFurnitureToMap(furnitureByName, lightsFromOtherLevels, ((HomeFurnitureGroup)piece).getFurniture());
                continue;
            }
            if (!isHomeAssistantEntity(piece.getName()) || !piece.isVisible())
                continue;
            boolean isLight = piece instanceof HomeLight;
            if (isLight && ((HomeLight)piece).getPower() == 0f)
                continue;
            if (!home.getEnvironment().isAllLevelsVisible() && piece.getLevel() != home.getSelectedLevel()) {
                if (isLight)
                    lightsFromOtherLevels.add(piece);
                continue;
            }
            if (!furnitureByName.containsKey(piece.getName()))
                furnitureByName.put(piece.getName(), new ArrayList<HomePieceOfFurniture>());
            furnitureByName.get(piece.getName()).add(piece);
        }
    }

    private void createHomeAssistantEntities() {
        Map<String, List<HomePieceOfFurniture>> furnitureByName = new HashMap<>();
        List<HomePieceOfFurniture> lightsFromOtherLevels = new ArrayList<>();
        addEligibleFurnitureToMap(furnitureByName, lightsFromOtherLevels, home.getFurniture());

        for (List<HomePieceOfFurniture> pieces : furnitureByName.values()) {
            Entity entity = new Entity(settings, pieces);
            entity.addPropertyChangeListener(Entity.Property.POSITION, new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent ev) {
                    repositionEntities();
                }
            });
            entity.addPropertyChangeListener(Entity.Property.ALWAYS_ON, new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent ev) {
                    buildLightsGroups();
                    propertyChangeSupport.firePropertyChange(Property.NUMBER_OF_RENDERS.name(), null, getNumberOfTotalRenders());
                }
            });
            entity.addPropertyChangeListener(Entity.Property.DISPLAY_FURNITURE_CONDITION, new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent ev) {
                    buildScenes();
                    propertyChangeSupport.firePropertyChange(Property.NUMBER_OF_RENDERS.name(), null, getNumberOfTotalRenders());
                }
            });
            entity.addPropertyChangeListener(Entity.Property.OPEN_FURNITURE_CONDITION, new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent ev) {
                    buildScenes();
                    propertyChangeSupport.firePropertyChange(Property.NUMBER_OF_RENDERS.name(), null, getNumberOfTotalRenders());
                }
            });

            if (entity.getIsLight())
                lightEntities.add(entity);
            else
                otherEntities.add(entity);
        }

        for (HomePieceOfFurniture piece : lightsFromOtherLevels)
            otherLevelsEntities.add(new Entity(settings, Arrays.asList(piece)));
    }

    private void buildLightsGroups() {
        lightsGroups.clear();

        // Group every light by the room that contains it. Each room then gets the
        // full set of on/off combinations of its lights rendered at night.
        Set<Entity> groupedLights = new HashSet<>();
        for (Room room : home.getRooms()) {
            if (!home.getEnvironment().isAllLevelsVisible() && room.getLevel() != home.getSelectedLevel())
                continue;
            String roomName = room.getName() != null && !room.getName().trim().isEmpty() ? room.getName() : room.getId();
            for (Entity entity : lightEntities) {
                HomePieceOfFurniture light = entity.getPiecesOfFurniture().get(0);
                if (room.containsPoint(light.getX(), light.getY(), 0) && room.getLevel() == light.getLevel()) {
                    lightsGroups.computeIfAbsent(roomName, key -> new ArrayList<>()).add(entity);
                    groupedLights.add(entity);
                }
            }
        }

        // Lights that aren't inside any room get their own group so they're still rendered.
        for (Entity entity : lightEntities) {
            if (!groupedLights.contains(entity))
                lightsGroups.put(entity.getName(), new ArrayList<>(Arrays.asList(entity)));
        }
    }

    private void buildScenes() {
        int oldNumberOfTotalRenders = getNumberOfTotalRenders();
        scenes = new Scenes(camera);
        scenes.setRenderingTimes(renderDateTimes);
        scenes.setEntitiesToShowOrHide(otherEntities.stream().filter(entity -> { return entity.getDisplayFurnitureCondition() != Entity.DisplayFurnitureCondition.ALWAYS; }).collect(Collectors.toList()));
        scenes.setEntitiesToOpenOrClose(otherEntities.stream().filter(entity -> { return entity.getOpenFurnitureCondition() != Entity.OpenFurnitureCondition.ALWAYS; }).collect(Collectors.toList()));
        propertyChangeSupport.firePropertyChange(Property.NUMBER_OF_RENDERS.name(), oldNumberOfTotalRenders, getNumberOfTotalRenders());
    }

    private boolean isHomeAssistantEntity(String name) {
        List<String> sensorPrefixes = Arrays.asList(
            "air_quality.",
            "alarm_control_panel.",
            "assist_satellite.",
            "binary_sensor.",
            "button.",
            "camera.",
            "climate.",
            "cover.",
            "device_tracker.",
            "fan.",
            "humidifier.",
            "input_boolean.",
            "input_button.",
            "lawn_mower.",
            "light.",
            "lock.",
            "media_player.",
            "remote.",
            "sensor.",
            "siren.",
            "switch.",
            "sun.",
            "todo.",
            "update.",
            "vacuum.",
            "valve.",
            "water_heater.",
            "weather."
        );

        if (name == null)
            return false;

        return sensorPrefixes.stream().anyMatch(name::startsWith);
    }

    private void build3dProjection() {
        cameraPosition = new Vector4d(camera.getX(), camera.getZ(), camera.getY(), 0);

        Transform3D yawRotation = new Transform3D();
        yawRotation.rotY(camera.getYaw());

        Transform3D pitchRotation = new Transform3D();
        pitchRotation.rotX(-camera.getPitch());

        perspectiveTransform = new Transform3D();
        perspectiveTransform.perspective(camera.getFieldOfView(), (double)renderWidth / renderHeight, 0.1, 100);
        perspectiveTransform.mul(pitchRotation);
        perspectiveTransform.mul(yawRotation);
    }

    private String getFloorplanImageExtention() {
        return this.imageFormat.name().toLowerCase();
    }

    private void generateTransparentImage(String fileName) throws IOException {
        BufferedImage image = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0);
        File imageFile = new File(fileName);
        ImageIO.write(image, "png", imageFile);
    }

    private BufferedImage postProcessImage(BufferedImage image, BufferedImage stencilMask) {
        BufferedImage processedImage = image;

        if (enableFloorPlanPostProcessing) {
            if (cropArea != null) {
                AutoCrop cropper = new AutoCrop();
                processedImage = cropper.crop(image, cropArea, maintainAspectRatio, renderWidth, renderHeight);
            }

            // Apply stamp before green removal, as stamp provides the definitive outer boundary.
            if (cropArea != null && stencilMask != null) {
                processedImage = applyFloorplanStamp(processedImage, stencilMask);
            }

            // Now, remove any green from the interior.
            processedImage = removeGreenBackground(processedImage);
        }
        
        return processedImage;
    }

    private BufferedImage processAndSaveFinalImage(BufferedImage image, BufferedImage stencilMask, String imageName) throws IOException {
        BufferedImage processedImage = postProcessImage(image, stencilMask);

        saveFloorPlanImage(processedImage, imageName, "png");
        propertyChangeSupport.firePropertyChange(Property.PREVIEW_UPDATE.name(), null, processedImage);
        return processedImage;
    }

    private BufferedImage postProcessRoomSelectorImage(BufferedImage image, BufferedImage stencilMask) {
        BufferedImage processedImage = image;

        if (enableFloorPlanPostProcessing) {
            // Mirror the floor plan image pipeline (crop, then stamp) so the room
            // selector aligns pixel-for-pixel with the base and light images. The
            // previous order stamped the full uncropped frame, which left the
            // cut-out mask offset from the cropped/scaled floor plan. There is no
            // green to strip here, so removeGreenBackground is not needed.
            if (cropArea != null) {
                AutoCrop cropper = new AutoCrop();
                processedImage = cropper.crop(image, cropArea, maintainAspectRatio, renderWidth, renderHeight);
            }

            if (cropArea != null && stencilMask != null) {
                processedImage = applyFloorplanStamp(processedImage, stencilMask);
            }
        }

        return processedImage;
    }

    private BufferedImage removeGreenBackground(BufferedImage image) {
        BufferedImage transparentImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                
                if (isBackgroundColor(rgb, AutoCrop.CROP_COLOR.getRGB(), transparencyThreshold)) {
                    transparentImage.setRGB(x, y, 0x00000000);
                } else {
                    // Keep original pixel, preserving any feathered alpha from the stamp
                    transparentImage.setRGB(x, y, rgb);
                }
            }
        }
        
        return transparentImage;
    }

    private boolean isStampBackgroundColor(int rgb) {
        // The stamp render paints sky and ground with the pure-green chroma key
        // (0,255,0). A daytime render keeps that key near pure green, but a night
        // render dims it to a dark green (e.g. 0,40,0) that no longer sits within
        // transparencyThreshold of pure green, which made the stamp come out
        // blank for night render times. The key stays strongly green-dominant at
        // any brightness, so detect the background by its green bias instead of
        // its distance to a fixed green; this also subsumes the pure-green case.
        // Interior false positives are harmless because the flood fill only cuts
        // out background that is connected to the image border.
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return g - r >= STAMP_BACKGROUND_GREEN_MARGIN && g - b >= STAMP_BACKGROUND_GREEN_MARGIN;
    }

    private boolean isBackgroundColor(int color1, int background, int tolerance) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (background >> 16) & 0xFF;
        int g2 = (background >> 8) & 0xFF;
        int b2 = background & 0xFF;

        return Math.abs(r1 - r2) <= tolerance && Math.abs(g1 - g2) <= tolerance && Math.abs(b1 - b2) <= tolerance;
    }

    private void updateEntityPositionsForCrop() {
        if (cropArea == null) return;
        
        int originalRenderWidth = this.renderWidth;
        int originalRenderHeight = this.renderHeight;
        
        try {
            if (!maintainAspectRatio) {
                this.renderWidth = cropArea.width;
                this.renderHeight = cropArea.height;
            }
            
            // Recalculate 3D projection and entity positions based on the new, cropped dimensions
            // This is necessary so the UI icons in the YAML file have the correct coordinates.
            repositionEntities();

            // The original entity positions are still needed for the actual crop calculation.
            // This part of the original logic seems to have been flawed, as it was recalculating
            // positions based on already calculated positions. The call to repositionEntities
            // handles the projection correctly. The entity.setPosition calls are now redundant
            // as repositionEntities will handle it. We will rely on the repositionEntities method
            // to correctly calculate the new icon positions based on the temporary cropped dimensions.

        } finally {
            // Restore the original render dimensions immediately so that all subsequent
            // image generation (`generateImage`, etc.) uses the full, uncropped size.
            this.renderWidth = originalRenderWidth;
            this.renderHeight = originalRenderHeight;
        }
    }

    private void saveRawRender(BufferedImage image, String name) throws IOException {
        String fileName = outputRendersDirectoryName + File.separator + name + ".png";
        File imageFile = new File(fileName);
        ImageIO.write(image, "png", imageFile);
    }

    private BufferedImage renderScene() throws IOException, InterruptedException {
        Map<Renderer, String> rendererToClassName = new HashMap<Renderer, String>() {{
            put(Renderer.SUNFLOW, "com.eteks.sweethome3d.j3d.PhotoRenderer");
            put(Renderer.YAFARAY, "com.eteks.sweethome3d.j3d.YafarayRenderer");
        }};
        photoRenderer = AbstractPhotoRenderer.createInstance(
            rendererToClassName.get(renderer),
            home, null, this.quality == Quality.LOW ? AbstractPhotoRenderer.Quality.LOW : AbstractPhotoRenderer.Quality.HIGH);
        BufferedImage image = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        photoRenderer.render(image, camera, null);
        if (photoRenderer != null) {
            photoRenderer.dispose();
            photoRenderer = null;
        }
        if (Thread.interrupted())
            throw new InterruptedException();

        return image;
    }

    private BufferedImage generateFloorPlanImage(BufferedImage baseImage, BufferedImage image, boolean createOverlayImage) throws IOException {
        if (!createOverlayImage) {
            return image;
        }

        BufferedImage overlay = new BufferedImage(baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_INT_ARGB);

        for(int x = 0; x < baseImage.getWidth(); x++) {
            for(int y = 0; y < baseImage.getHeight(); y++) {
                int basePixel = baseImage.getRGB(x, y);
                if (((basePixel >> 24) & 0xff) == 0) {
                    continue;
                }
                overlay.setRGB(x, y, image.getRGB(x, y) | 0xFF000000);
            }
        }
        return overlay;
    }

    private void saveFloorPlanImage(BufferedImage image, String name, String extension) throws IOException {
        File floorPlanFile = new File(outputFloorplanDirectoryName + File.separator + name + "." + extension);
        ImageIO.write(image, extension, floorPlanFile);
    }

    private BufferedImage generateRedTintedImage(BufferedImage image, String imageName, BufferedImage stencilMask) throws IOException {
        BufferedImage tintedImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);

        for(int x = 0; x < image.getWidth(); x++) {
            for(int y = 0; y < image.getHeight(); y++) {
                int rgb = image.getRGB(x, y);
                if (rgb == 0)
                    continue;
                Color original = new Color(rgb, true);
                float hsb[] = Color.RGBtoHSB(original.getRed(), original.getGreen(), original.getBlue(), null);
                Color redTint = Color.getHSBColor(1.0f, 0.75f, hsb[2]);
                tintedImage.setRGB(x, y, redTint.getRGB());
            }
        }

        BufferedImage processedTintedImage = postProcessImage(tintedImage, stencilMask);
        saveFloorPlanImage(processedTintedImage, imageName + ".red", "png");
        return processedTintedImage;
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_ARRAY[b >>> 4];
            hexChars[i * 2 + 1] = HEX_ARRAY[b & 0x0F];
        }
        return new String(hexChars);
    }

    private String renderHash(String imageName) throws IOException {
        return renderHash(imageName, false);
    }

    private String renderHash(String imageName, boolean forcePng) throws IOException {
        String imageExtension = forcePng ? "png" : getFloorplanImageExtention();
        byte[] content = Files.readAllBytes(Paths.get(outputFloorplanDirectoryName + File.separator + imageName + "." + imageExtension));
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(content);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Long.toString(System.currentTimeMillis() / 1000L);
        }
    }

    private String generateLightYaml(Scene scene, List<Entity> lights, List<Entity> onLights, String imageName) throws IOException {
        return generateLightYaml(scene, lights, onLights, imageName, true);
    }

    private String generateTitle(Scene scene, List<Entity> onLights) {
        List<String> titleParts = onLights != null ?  onLights.stream().map(Entity::getName).collect(Collectors.toList()) : new ArrayList<>(Arrays.asList("Base"));
        titleParts.add(0, scene.getTitle());

        return titleParts.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining(", "));
    }

    private String generateLightYaml(Scene scene, List<Entity> lights, List<Entity> onLights, String imageName, boolean includeMixBlend) throws IOException {
        String conditions = "";
        if (onLights != null) {
            for (Entity light : onLights) {
                conditions += String.format(
                    "      - condition: state\n" +
                    "        entity: %s\n" +
                    "        state: 'on'\n",
                    light.getName());
            }
        }
        conditions += scene.getConditions();
        if (conditions.length() == 0)
            conditions = "      []\n";

        return String.format(
            "  - type: conditional\n" +
            "    title: %s\n" +
            "    conditions:\n%s" +
            "    elements:\n" +
            "      - type: image\n" +
            "        tap_action:\n" +
            "          action: none\n" +
            "        hold_action:\n" +
            "          action: none\n" +
            "        image: /local/floorplan/%s.%s?version=%s\n" +
            "        filter: none\n" +
            "        style:\n" +
            "          left: 50%%\n" +
            "          top: 50%%\n" +
            "          width: 100%%\n%s",
            generateTitle(scene, onLights), conditions, normalizePath(imageName),
            getFloorplanImageExtention(), renderHash(imageName),
            includeMixBlend ? "          mix-blend-mode: lighten\n" : "");
    }

    private String generateRgbLightYaml(Scene scene, Entity light, String imageName) throws IOException {
        String lightName = light.getName();

        return String.format(
            "  - type: conditional\n" +
            "    title: %s\n" +
            "    conditions:\n" +
            "      - condition: state\n" +
            "        entity: %s\n" +
            "        state: 'on'\n%s" +
            "    elements:\n" +
            "      - type: custom:config-template-card\n" +
            "        variables:\n" +
            "          LIGHT_STATE: states['%s'].state\n" +
            "          COLOR_MODE: states['%s'].attributes.color_mode\n" +
            "          LIGHT_COLOR: states['%s'].attributes.hs_color\n" +
            "          BRIGHTNESS: states['%s'].attributes.brightness\n" +
            "          isInColoredMode: colorMode => ['hs', 'rgb', 'rgbw', 'rgbww', 'white', 'xy'].includes(colorMode)\n" +
            "        entities:\n" +
            "          - %s\n" +
            "        element:\n" +
            "          type: image\n" +
            "          image: >-\n" +
            "              ${!isInColoredMode(COLOR_MODE) || (isInColoredMode(COLOR_MODE) && LIGHT_COLOR && LIGHT_COLOR[0] == 0 && LIGHT_COLOR[1] == 0) ?\n" +
            "              '/local/floorplan/%s.png?version=%s' :\n" +
            "              '/local/floorplan/%s.png?version=%s' }\n" +
            "        style:\n" +
            "          filter: '${ \"hue-rotate(\" + (isInColoredMode(COLOR_MODE) && LIGHT_COLOR ? LIGHT_COLOR[0] : 0) + \"deg) saturate(\" + (LIGHT_COLOR ? LIGHT_COLOR[1] / 100 : 1) + \")\"}'\n" +
            "          opacity: '${LIGHT_STATE === ''on'' ? (BRIGHTNESS / 255) : ''100''}'\n" +
            "          mix-blend-mode: lighten\n" +
            "          pointer-events: none\n" +
            "          left: 50%%\n" +
            "          top: 50%%\n" +
            "          width: 100%%\n",
            generateTitle(scene, Arrays.asList(light)),
            lightName, scene.getConditions(), lightName, lightName, lightName, lightName, lightName,
            normalizePath(imageName), renderHash(imageName, true), normalizePath(imageName) + ".red", renderHash(imageName + ".red", true));
    }

    private String normalizePath(String fileName) {
        if (File.separator.equals("/"))
            return fileName;
        return fileName.replace('\\', '/');
    }

    private void turnOffLightsFromOtherLevels() {
        otherLevelsEntities.forEach(entity -> entity.setLightPower(false));
    }

    private void restoreEntityConfiguration() {
        Stream.of(lightEntities, otherEntities, otherLevelsEntities).flatMap(Collection::stream)
            .forEach(Entity::restoreConfiguration);
    }

    private void removeAlwaysOnLights(List<Entity> inputList) {
        ListIterator<Entity> iter = inputList.listIterator();

        while (iter.hasNext()) {
            if (iter.next().getAlwaysOn())
                iter.remove();
        }
    }

    public List<List<Entity>> getCombinations(List<Entity> inputSet) {
        List<List<Entity>> combinations = new ArrayList<>();
        List<Entity> inputList = new ArrayList<>(inputSet);
        removeAlwaysOnLights(inputList);
        for (Entity entity : inputList)
            combinations.add(Arrays.asList(entity));
        return combinations;
    }

    private Point2d getFurniture2dLocation(HomePieceOfFurniture piece) {
        float levelOffset = piece.getLevel() != null ? piece.getLevel().getElevation() : 0;
        Vector4d objectPosition = new Vector4d(piece.getX(), (((piece.getElevation() * 2) + piece.getHeight()) / 2) + levelOffset, piece.getY(), 0);

        objectPosition.sub(cameraPosition);
        perspectiveTransform.transform(objectPosition);
        objectPosition.scale(1 / objectPosition.w);

        return new Point2d((objectPosition.x * 0.5 + 0.5) * 100.0, (objectPosition.y * 0.5 + 0.5) * 100.0);
    }


    private String generateEntitiesYaml() {
        return Stream.concat(lightEntities.stream(), otherEntities.stream())
            .map(Entity::buildYaml).collect(Collectors.joining());
    }

    private void repositionEntities() {
        build3dProjection();
        calculateEntityPositions();
        moveEntityIconsToAvoidIntersection();
    }

    private void calculateEntityPositions() {
        Stream.concat(lightEntities.stream(), otherEntities.stream())
            .forEach(entity -> {
                Point2d entityCenter = new Point2d();
                for (HomePieceOfFurniture piece : entity.getPiecesOfFurniture())
                    entityCenter.add(getFurniture2dLocation(piece));
                entityCenter.scale(1.0 / entity.getPiecesOfFurniture().size());

                entity.setPosition(entityCenter, false);
            });
    }

    private boolean doStateIconsIntersect(Entity first, Entity second) {
        final double STATE_ICON_RADIUS_INCLUDING_MARGIN = 25.0;

        Point2d firstPositionInPixels = new Point2d(first.getPosition().x / 100.0 * renderWidth, first.getPosition().y / 100 * renderHeight);
        Point2d secondPositionInPixels = new Point2d(second.getPosition().x / 100.0 * renderWidth, second.getPosition().y / 100 * renderHeight);

        double x = Math.pow(firstPositionInPixels.x - secondPositionInPixels.x, 2) + Math.pow(firstPositionInPixels.y - secondPositionInPixels.y, 2);

        return x <= Math.pow(STATE_ICON_RADIUS_INCLUDING_MARGIN * 2, 2);
    }

    private boolean doesStateIconIntersectWithSet(Entity entity, Set<Entity> entities) {
        for (Entity other : entities) {
            if (doStateIconsIntersect(entity, other))
                return true;
        }
        return false;
    }

    private Set<Entity> setWithWhichStateIconIntersects(Entity entity, List<Set<Entity>> entities) {
        for (Set<Entity> set : entities) {
            if (doesStateIconIntersectWithSet(entity, set))
                return set;
        }
        return null;
    }

    private Optional<Entity> stateIconWithWhichStateIconIntersects(Entity entity) {
        return Stream.concat(lightEntities.stream(), otherEntities.stream())
            .filter(other -> {
                if (entity == other)
                    return false;
                return doStateIconsIntersect(entity, other);
            }).findFirst();
    }

    private List<Set<Entity>> findIntersectingStateIcons() {
        List<Set<Entity>> intersectingStateIcons = new ArrayList<Set<Entity>>();

        Stream.concat(lightEntities.stream(), otherEntities.stream())
            .forEach(entity -> {
                Set<Entity> intersectingSet = setWithWhichStateIconIntersects(entity, intersectingStateIcons);
                if (intersectingSet != null) {
                    intersectingSet.add(entity);
                    return;
                }
                Optional<Entity> intersectingStateIcon = stateIconWithWhichStateIconIntersects(entity);
                if (!intersectingStateIcon.isPresent())
                    return;
                Set<Entity> intersectingGroup = new HashSet<Entity>();
                intersectingGroup.add(entity);
                intersectingGroup.add(intersectingStateIcon.get());
                intersectingStateIcons.add(intersectingGroup);
            });

        return intersectingStateIcons;
    }

    private Point2d getCenterOfStateIcons(Set<Entity> entities) {
        Point2d centerPosition = new Point2d();
        for (Entity entity : entities )
            centerPosition.add(entity.getPosition());
        centerPosition.scale(1.0 / entities.size());
        return centerPosition;
    }

    private void separateStateIcons(Set<Entity> entities) {
        final double STEP_SIZE = 2.0;

        Point2d centerPosition = getCenterOfStateIcons(entities);

        for (Entity entity : entities) {
            Vector2d direction = new Vector2d(entity.getPosition().x - centerPosition.x, entity.getPosition().y - centerPosition.y);

            if (direction.length() == 0) {
                double[] randomRepeatableDirection = { entity.getId().hashCode(), entity.getName().hashCode() };
                direction.set(randomRepeatableDirection);
            }

            direction.normalize();
            direction.x = direction.x * (100.0 * (STEP_SIZE / renderWidth));
            direction.y = direction.y * (100.0 * (STEP_SIZE / renderHeight));
            entity.move(direction);
        }
    }

    private void moveEntityIconsToAvoidIntersection() {
        for (int i = 0; i < 100; i++) {
            List<Set<Entity>> intersectingStateIcons = findIntersectingStateIcons();
            if (intersectingStateIcons.size() == 0)
                break;
            for (Set<Entity> set : intersectingStateIcons)
                separateStateIcons(set);
        }
    }

    private boolean isCeilingLight(Entity light) {
        return light.getName().toLowerCase().contains(CEILING_LIGHT_NAME_KEYWORD);
    }

    private BufferedImage processImage(String imageName, List<Entity> onLights, BufferedImage baseImage, BufferedImage stencilMask) throws IOException, InterruptedException {
        File finalImageFile = new File(outputFloorplanDirectoryName + File.separator + imageName + "." + getFloorplanImageExtention());
        File rawRenderFile = new File(outputRendersDirectoryName + File.separator + imageName + ".png");

        if (useExistingRenders && finalImageFile.exists()) {
            propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(++numberOfCompletedRenders, "Skipping " + imageName + "..."));
            if (rawRenderFile.exists()) {
                return ImageIO.read(rawRenderFile);
            }
            return ImageIO.read(finalImageFile);
        }

        propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(numberOfCompletedRenders, "Rendering " + imageName + "..."));

        BufferedImage rawImage;
        if (onLights == null) { // Special case for base_night
             rawImage = generateNightBaseImage();
        } else {
            Map<HomeLight, Float> originalPowers = new HashMap<>();
            try {
                // Determine which lights should be on for this render pass
                Set<Entity> lightsToTurnOn = new HashSet<>(onLights);
                lightEntities.stream()
                    .filter(Entity::getAlwaysOn)
                    .forEach(lightsToTurnOn::add);

                for (Entity light : lightEntities) {
                    boolean isLightOn = lightsToTurnOn.contains(light);

                    // Save original power for all underlying HomeLight objects
                    for (HomePieceOfFurniture piece : light.getPiecesOfFurniture()) {
                        if (piece instanceof HomeLight) {
                            originalPowers.put((HomeLight)piece, ((HomeLight)piece).getPower());
                        }
                    }

                    // Set the entity state. This likely also sets the HomeLight power to 0.0 or 1.0
                    light.setLightPower(isLightOn);

                    // If the light is on, override the power with the desired intensity
                    if (isLightOn) {
                        float intensity = isCeilingLight(light)
                            ? renderCeilingLightsIntensity
                            : renderOtherLightsIntensity;
                        for (HomePieceOfFurniture piece : light.getPiecesOfFurniture()) {
                            if (piece instanceof HomeLight) {
                                ((HomeLight)piece).setPower(intensity / 100.0f);
                            }
                        }
                    }
                }

                rawImage = renderScene();

            } finally {
                // Restore all original power values after rendering
                for (Map.Entry<HomeLight, Float> entry : originalPowers.entrySet()) {
                    entry.getKey().setPower(entry.getValue());
                }
            }
        }

        saveRawRender(rawImage, imageName);

        BufferedImage processedImage;
        if (baseImage != null) {
            boolean isRgb = onLights.stream().anyMatch(Entity::getIsRgb);
            processedImage = generateFloorPlanImage(baseImage, rawImage, isRgb);
        } else {
            processedImage = rawImage;
        }

        processAndSaveFinalImage(processedImage, stencilMask, imageName);

        propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(++numberOfCompletedRenders, "Finished " + imageName + "."));

        return rawImage;
    }

    private BufferedImage generateNightBaseImage() throws IOException, InterruptedException {
        Map<HomeLight, Float> originalPowers = new HashMap<>();
        try {
            Stream.concat(lightEntities.stream(), otherEntities.stream())
                .filter(entity -> entity.getName().startsWith("light."))
                .forEach(light -> {
                    if (!light.getAlwaysOn()) {
                        light.setLightPower(true);

                        for (HomePieceOfFurniture piece : light.getPiecesOfFurniture()) {
                            if (piece instanceof HomeLight) {
                                HomeLight homeLight = (HomeLight) piece;
                                originalPowers.put(homeLight, homeLight.getPower());
                                float intensity = isCeilingLight(light)
                                    ? ceilingLightsIntensity
                                    : otherLightsIntensity;
                                homeLight.setPower(intensity / 100.0f);
                            }
                        }
                    }
                });
            return renderScene();
        } finally {
            for (Map.Entry<HomeLight, Float> entry : originalPowers.entrySet()) {
                entry.getKey().setPower(entry.getValue());
            }
        }
    }

    private Point2d getRoom2dLocation(float x, float y, float elevation) {
        Vector4d objectPosition = new Vector4d(x, elevation, y, 0);

        objectPosition.sub(cameraPosition);
        perspectiveTransform.transform(objectPosition);
        objectPosition.scale(1 / objectPosition.w);

        return new Point2d((objectPosition.x * 0.5 + 0.5) * renderWidth, (objectPosition.y * 0.5 + 0.5) * renderHeight);
    }

    private void generateRoomSelectorImages(BufferedImage stencilMask) throws IOException {
        // Floor outline: traced at the room's floor elevation.
        generateRoomOutlineImages(stencilMask, "floorplan_selected",
            room -> room.getLevel() != null ? room.getLevel().getElevation() : 0f,
            "Generating room selectors...", "Finished generating room selectors.");
    }

    private void generateRoomTopSelectorImages(BufferedImage stencilMask) throws IOException {
        // Ceiling outline: traced at the wall-to-ceiling boundary, i.e. the
        // floor elevation plus the level's wall height.
        generateRoomOutlineImages(stencilMask, "room_selected",
            room -> room.getLevel() != null ? room.getLevel().getElevation() + room.getLevel().getHeight() : 0f,
            "Generating room top selectors...", "Finished generating room top selectors.");
    }

    private void generateRoomOutlineImages(BufferedImage stencilMask, String outputSelectedDirectoryLeafName,
            Function<Room, Float> roomOutlineElevation, String startStatusText, String finishStatusText) throws IOException {
        String outputSelectedDirectoryName = outputDirectoryName + File.separator + outputSelectedDirectoryLeafName;
        Files.createDirectories(Paths.get(outputSelectedDirectoryName));

        propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(numberOfCompletedRenders, startStatusText));

        for (Room room : home.getRooms()) {
            if (!home.getEnvironment().isAllLevelsVisible() && room.getLevel() != home.getSelectedLevel())
                continue;

            BufferedImage roomImage = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = roomImage.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{9}, 0));

            Polygon polygon = new Polygon();
            float elevation = roomOutlineElevation.apply(room);
            for (float[] point : room.getPoints()) {
                Point2d p = getRoom2dLocation(point[0], point[1], elevation);
                polygon.addPoint((int) p.x, (int) p.y);
            }

            g2d.drawPolygon(polygon);
            g2d.dispose();

            BufferedImage processedImage = postProcessRoomSelectorImage(roomImage, stencilMask);

            String roomName = room.getName() != null && !room.getName().trim().isEmpty() ? room.getName() : room.getId();
            File roomFile = new File(outputSelectedDirectoryName + File.separator + roomName.toLowerCase() + ".png");
            ImageIO.write(processedImage, "png", roomFile);
        }

        propertyChangeSupport.firePropertyChange(Property.PROGRESS_UPDATE.name(), null, new ProgressUpdate(++numberOfCompletedRenders, finishStatusText));
    }
};
