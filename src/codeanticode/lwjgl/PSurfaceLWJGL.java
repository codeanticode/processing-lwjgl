/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2018 Andres Colubri

  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation, version 2.1.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General
  Public License along with this library; if not, write to the
  Free Software Foundation, Inc., 59 Temple Place, Suite 330,
  Boston, MA  02111-1307  USA
*/

package codeanticode.lwjgl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PGraphics;
import processing.core.PImage;
import processing.core.PSurface;
import processing.event.Event;
import processing.event.KeyEvent;
import processing.event.MouseEvent;
import processing.opengl.PGL;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class PSurfaceLWJGL implements PSurface {

  private static final boolean DEBUG_GLFW = false;

  @SuppressWarnings("FieldCanBeLocal")
  private GLDebugMessageCallback debugCallback;

  private PApplet sketch;
  private final PGraphics graphics;
  private final PLWJGL pgl;

  /**
   * ScaledSketch instance which helps with DPI scaling tasks.
   */
  private ScaledSketch scaledSketch;

  private Rectangle requestedSketchSize;
  private Rectangle windowPosSize;
  private Rectangle frameBufferSize;
  private float contentScale;

  private boolean external;

  private long window;
  private long monitor;

  /**
   * Bounds of the display where the sketch should initially open
   */
  private Rectangle monitorRect;

  /**
   * Refresh rate of the display where the sketch should initially open
   */
  private int monitorRefreshRate;

  /**
   * Bounds of the whole screen area if display is SPAN, null otherwise
   */
  private Rectangle desktopBounds;

  /**
   * Whether the main loop should keep running. Set to false to break out of
   * the main loop.
   */
  private volatile boolean threadRunning;

  private float frameRate = 60f;
  private int swapInterval;
  private boolean swapIntervalChanged;


  /*
    Lifecycle:

    1. constructor
    2. initFrame() or initOffscreen()
    3. placePresent() or placeWindow()
    4. if (running external) setupExternalMessages()
    5. setVisible()
    6. startThread()
    7. stopThread()
   */


  PSurfaceLWJGL(PGraphics graphics) {
    this.graphics = graphics;
    this.pgl = (PLWJGL) ((PGraphicsLWJGL) graphics).pgl;
  }


  //region Callback handling

  // Keep references to all callbacks, otherwise they might get
  // removed by the Garbage Collector
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private final List<Callback> callbacks = new ArrayList<>();


  private <T extends Callback> void addCallback(Consumer<T> setter, T callback) {
    callbacks.add(callback);
    setter.accept(callback);
  }


  private <T extends Callback> void addWindowCallback(BiConsumer<Long,T> setter, T callback) {
    callbacks.add(callback);
    setter.accept(window, callback);
  }

  //endregion


  @Override
  public void initOffscreen(PApplet sketch) {
    throw new AssertionError("This surface does not support offscreen rendering");
  }


  @Override
  public void initFrame(PApplet sketch) {
    this.sketch = sketch;

    if (!glfwInit()) {
      PGraphics.showException("Unable to initialize GLFW");
    }

    if (DEBUG_GLFW) {
      System.out.println("GLFW initialized: " + glfwGetVersionString());
    }

    // GLFW error callback
    addCallback(GLFW::glfwSetErrorCallback, GLFWErrorCallback
      .create((error, description) -> {
        String message = MemoryUtil.memUTF8(description);
        PGraphics.showWarning("GLFW error " + error + ": " + message);
      }));

    // TODO initIcons();
    initDisplay();

    initWindow();
    initInputListeners();

    // Initialize framerate timer
    Sync.initialise();
  }


  @Override
  public Object getNative() {
    return window;
  }


  @Override
  public void setTitle(String title) {
    GLFW.glfwSetWindowTitle(window, title);
  }


  @Override
  public void setVisible(boolean visible) {
    if (visible) {
      glfwShowWindow(window);
    } else {
      glfwHideWindow(window);
    }
  }


  @Override
  public void setResizable(boolean resizable) {
    int value = resizable ? GLFW_TRUE : GLFW_FALSE;
    glfwSetWindowAttrib(window, GLFW_RESIZABLE, value);
  }


  @Override
  public void setAlwaysOnTop(boolean always) {
    int value = always ? GLFW_TRUE : GLFW_FALSE;
    glfwSetWindowAttrib(window, GLFW_FLOATING, value);
  }


  @Override
  public void setIcon(PImage icon) {
    // TODO: Set window icon
  }


  private void initDisplay() {
    try (MemoryStack stack = stackPush()) {
      IntBuffer pX = stack.mallocInt(1);
      IntBuffer pY = stack.mallocInt(1);

      // Get the window monitor
      PointerBuffer monitorList = glfwGetMonitors();

      // Check if we got at least one monitor
      if (monitorList == null || monitorList.limit() == 0) {
        // TODO: JOGL crashes on NPE in this case, should LWJGL too? [jv 2018-10-06]
        PGraphics.showException("No monitors found");
      }

      if (DEBUG_GLFW) {
        FloatBuffer scaleX = stack.mallocFloat(1);
        FloatBuffer scaleY = stack.mallocFloat(1);
        for (int i = 0; i < monitorList.limit(); i++) {
          long m = monitorList.get(i);
          String name = glfwGetMonitorName(m);
          glfwGetMonitorPos(m, pX, pY);
          glfwGetMonitorContentScale(m, scaleX, scaleY);
          GLFWVidMode mode = glfwGetVideoMode(m);
          int w = 0;
          int h = 0;
          int refresh = 0;
          if (mode != null) {
            w = mode.width();
            h = mode.height();
            refresh = mode.refreshRate();
          }
          System.out.format("Display %d is %s { size: %dx%d, refresh: %d hz, pos: (%d,%d), scale: %.02fx%.02f }%n",
                            i + 1, name, w, h, refresh,
                            pX.get(0), pY.get(0), scaleX.get(0), scaleY.get(0));
        }
      }

      // Init with default monitor
      long monitor = monitorList.get(0);

      int displayNum = sketch.sketchDisplay();
      if (displayNum == PConstants.SPAN) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < monitorList.limit(); i++) {
          long m = monitorList.get(i);
          glfwGetMonitorPos(m, pX, pY);
          GLFWVidMode mode = glfwGetVideoMode(m);
          if (mode != null) {
            minX = Math.min(minX, pX.get(0));
            minY = Math.min(minY, pY.get(0));
            maxX = Math.max(maxX, pX.get(0) + mode.width());
            maxY = Math.max(maxY, pY.get(0) + mode.height());
          }
        }
        desktopBounds = new Rectangle(minX, minY, maxX - minX, maxY - minY);
        if (DEBUG_GLFW) {
          System.out.println("GLFW screen area is " + desktopBounds.toString());
        }
      } else if (displayNum > 0) {
        if (displayNum <= monitorList.limit()) {
          monitor = monitorList.get(displayNum - 1);
        } else {
          System.err.format("Display %d does not exist, "
                              + "using the default display instead.%n", displayNum);
          for (int i = 0; i < monitorList.limit(); i++) {
            long m = monitorList.get(i);
            String name = glfwGetMonitorName(m);
            glfwGetMonitorPos(m, pX, pY);
            GLFWVidMode mode = glfwGetVideoMode(m);
            int w = 0;
            int h = 0;
            if (mode != null) {
              w = mode.width();
              h = mode.height();
            }
            System.err.format("Display %d is %s { size: %dx%d, pos: %d,%d }%n",
                              i + 1, name, w, h, pX.get(0), pY.get(0));
          }
        }
      }

      glfwGetMonitorPos(monitor, pX, pY);
      GLFWVidMode mode = glfwGetVideoMode(monitor);
      if (mode == null) {
        // TODO: Should this crash? [jv 2018-10-06]
        PGraphics.showException("Could not retrieve monitor resolution");
      }
      monitorRect = new Rectangle(pX.get(0), pY.get(0),
                                  mode.width(), mode.height());
      monitorRefreshRate = mode.refreshRate();
      if (DEBUG_GLFW) {
        System.out.println("GLFW monitor rect: " + monitorRect);
        System.out.println("GLFW monitor refresh rate: " + monitorRefreshRate);
      }

      this.monitor = monitor;
    }
  }


  private void initWindow() {

    glfwDefaultWindowHints();
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

    glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);

    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);

    glfwWindowHint(GLFW_ALPHA_BITS, PGL.REQUESTED_ALPHA_BITS);
    glfwWindowHint(GLFW_DEPTH_BITS, PGL.REQUESTED_DEPTH_BITS);
    glfwWindowHint(GLFW_STENCIL_BITS, PGL.REQUESTED_STENCIL_BITS);
    // TODO: we should probably request 1 here, render to a multisampled FBO
    //       and blit the pixels to the screen [jv 2018-11-04]
    pgl.reqNumSamples = PGL.smoothToSamples(graphics.smooth);
    glfwWindowHint(GLFW_SAMPLES, pgl.reqNumSamples);

    if (sketch.sketchDisplay() == PConstants.SPAN) {

      PApplet.hideMenuBar();

      glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
      window = glfwCreateWindow(desktopBounds.w, desktopBounds.h, "Sketch", NULL, NULL);
      glfwSetWindowPos(window, desktopBounds.x, desktopBounds.y);

    } else if (sketch.sketchFullScreen()) {

      PApplet.hideMenuBar();

      GLFWVidMode mode = glfwGetVideoMode(monitor);
      if (mode == null) {
        PGraphics.showException("Could not retrieve monitor resolution");
      }
      glfwWindowHint(GLFW_RED_BITS, mode.redBits());
      glfwWindowHint(GLFW_GREEN_BITS, mode.greenBits());
      glfwWindowHint(GLFW_BLUE_BITS, mode.blueBits());
      glfwWindowHint(GLFW_REFRESH_RATE, mode.refreshRate());
      window = glfwCreateWindow(mode.width(), mode.height(),
                                "Sketch", monitor, NULL);
    } else {

      // Save the requested size to warn later in case the window is
      // resized by the system to fit the screen
      requestedSketchSize = new Rectangle();
      requestedSketchSize.w = sketch.sketchWidth();
      requestedSketchSize.h = sketch.sketchHeight();

      // We don't know on which monitor the window will open, create the window
      // first, load content scale and then set correct size
      window = glfwCreateWindow(100, 100, "Sketch", NULL, NULL);

      // Move to the selected monitor to get the right content scale and framebuffer size
      glfwSetWindowPos(window,
                       monitorRect.x + (monitorRect.w + 50) / 2,
                       monitorRect.y + (monitorRect.h + 50) / 2);
    }

    // Load the initial size and scale, later handled by callbacks
    try (MemoryStack stack = stackPush()) {
      IntBuffer w = stack.mallocInt(1);
      IntBuffer h = stack.mallocInt(1);
      FloatBuffer scale = stack.mallocFloat(1);

      { // Init content scale, use only X
        glfwGetWindowContentScale(window, scale, null);
        contentScale = scale.get(0);
        if (DEBUG_GLFW) {
          System.out.println("GLFW window content scale: " + contentScale);
        }
      }

      this.scaledSketch = new ScaledSketch(sketch, graphics, contentScale);

      if (!sketch.sketchFullScreen() && sketch.sketchDisplay() != PConstants.SPAN) {
        int windowWidth = scaledSketch.sketchToWindowUnits(sketch.sketchWidth());
        int windowHeight = scaledSketch.sketchToWindowUnits(sketch.sketchHeight());
        glfwSetWindowSize(window, windowWidth, windowHeight);
      }

      { // Init framebuffer size
        glfwGetFramebufferSize(window, w, h);

        int width = w.get(0);
        int height = h.get(0);

        frameBufferSize = new Rectangle();
        frameBufferSize.w = width;
        frameBufferSize.h = height;

        if (DEBUG_GLFW) {
          System.out.println("GLFW framebuffer size: " + width + " " + height);
        }
      }

      { // Init window size
        glfwGetWindowSize(window, w, h);
        windowPosSize = new Rectangle();
        windowPosSize.w = w.get(0);
        windowPosSize.h = h.get(0);
        if (DEBUG_GLFW) {
          System.out.println("GLFW window size: " + windowPosSize.w + " " + windowPosSize.h);
        }
      }

      // Forward initial size and scale to the sketch
      scaledSketch.updateSketchSize(contentScale, frameBufferSize.w, frameBufferSize.h);

      // Set initial refresh rate to be monitor refresh rate
      setFrameRate(this.monitorRefreshRate);
    }

    // Window Callbacks
    addWindowCallback(GLFW::glfwSetFramebufferSizeCallback, GLFWFramebufferSizeCallback
      .create((window1, width, height) -> {
        // Size is zero when minimized (at least on Windows)
        if (width != 0 && height != 0 && !pgl.presentMode()) {
          frameBufferSize.w = width;
          frameBufferSize.h = height;
          scaledSketch.updateSketchSize(contentScale, frameBufferSize.w, frameBufferSize.h);
        }
        if (DEBUG_GLFW) {
          System.out.println("GLFW framebuffer size changed: " + width + " " + height);
        }
      }));

    addWindowCallback(GLFW::glfwSetWindowPosCallback, GLFWWindowPosCallback
      .create((window1, xpos, ypos) -> {
        windowPosSize.x = xpos;
        windowPosSize.y = ypos;
        if (external) {
          sketch.frameMoved(xpos, ypos);
        }
      }));

    addWindowCallback(GLFW::glfwSetWindowSizeCallback, GLFWWindowSizeCallback
      .create((window1, width, height) -> {
        // Size is zero when minimized (at least on Windows)
        if (width != 0 && height != 0) {
          windowPosSize.w = width;
          windowPosSize.h = height;
        }
        if (DEBUG_GLFW) {
          System.out.println("GLFW window size changed: " + width + " " + height);
        }
      }));

    addWindowCallback(GLFW::glfwSetWindowContentScaleCallback, GLFWWindowContentScaleCallback
      .create((window1, xscale, yscale) -> {
        if (contentScale != xscale && !pgl.presentMode()) {
          contentScale = xscale;

          // Load framebuffer size
          try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            frameBufferSize.w = w.get(0);
            frameBufferSize.h = h.get(0);
          }
          scaledSketch.updateSketchSize(contentScale, frameBufferSize.w, frameBufferSize.h);
        }
        if (DEBUG_GLFW) {
          System.out.println("GLFW window scale changed: " + contentScale);
        }
      }));

    addWindowCallback(GLFW::glfwSetWindowCloseCallback, GLFWWindowCloseCallback
      .create(window1 -> sketch.exit()));

    addWindowCallback(GLFW::glfwSetWindowFocusCallback, GLFWWindowFocusCallback
      .create((window1, focused) -> {
        sketch.focused = focused;
        if (focused) {
          sketch.focusGained();
        } else {
          sketch.focusLost();
        }
        if (DEBUG_GLFW) {
          System.out.println("GLFW window focus changed: " + focused);
        }
      }));


    addWindowCallback(GLFW::glfwSetWindowRefreshCallback, GLFWWindowRefreshCallback
      .create(window1 -> {
        if (!sketch.isLooping()) {
          sketch.redraw();
        }
        if (DEBUG_GLFW) {
          System.out.println("GLFW window redraw notification");
        }
        if (sketch.frameCount > 0) {
          // Redraw callback fires for the first time right after making the
          // window visible. Don't redraw from here before setup() ran.
          handleDraw();
        }
      }));
  }


  //region Input Handling

  private int mouseX;
  private int mouseY;
  private int pressedMouseButton;
  private int modifiers;

  // MACOSX: CTRL + Left Mouse is converted to Right Mouse. This boolean keeps
  // track of whether the conversion happened on PRESS, because we should report
  // the same button during DRAG and on RELEASE, even though CTRL might have
  // been released already. Otherwise the events are inconsistent, e.g.
  // Left Pressed - Left Drag - CTRL Pressed - Right Drag - Right Released.
  // See: https://github.com/processing/processing/issues/5672
  private boolean macosxLeftButtonWithCtrlPressed;

  // Detecting mouse clicks - PRESS and RELEASE events must not be farther
  // away from each other than the limit, measured separately in X and in Y.
  // This is what AWT does, however PRESS and RELEASE does not have to be on
  // the same pixel; clicking like that is hard, especially on e.g. a touchpad.
  private static final int MOUSE_CLICK_CANCEL_DIST = 7;
  private int mousePressedX;
  private int mousePressedY;

  private int convertModifierBits(int glfwBits) {
    return glfwBits & 0b0001 |  // CTRL
      glfwBits & 0b0010 |       // SHIFT
      glfwBits & 0b1000 >> 1 |  // META
      glfwBits & 0b0100 << 1;   // ALT
  }


  private char convertKey(int glfwKey, boolean uppercase) {
    switch (glfwKey) {

    case GLFW_KEY_UP:
    case GLFW_KEY_DOWN:
    case GLFW_KEY_LEFT:
    case GLFW_KEY_RIGHT:
    case GLFW_KEY_LEFT_ALT:
    case GLFW_KEY_RIGHT_ALT:
    case GLFW_KEY_LEFT_CONTROL:
    case GLFW_KEY_RIGHT_CONTROL:
    case GLFW_KEY_LEFT_SHIFT:
    case GLFW_KEY_RIGHT_SHIFT:
      return PConstants.CODED;

    // Keys with constants
    case GLFW_KEY_BACKSPACE:
      return PConstants.BACKSPACE;
    case GLFW_KEY_TAB:
      return PConstants.TAB;
    case GLFW_KEY_ENTER:
    case GLFW_KEY_KP_ENTER:
      return PConstants.ENTER;
    case GLFW_KEY_ESCAPE:
      return PConstants.ESC;
    case GLFW_KEY_DELETE:
      return PConstants.DELETE;

    case GLFW_KEY_KP_ADD:
      return '+';
    case GLFW_KEY_KP_SUBTRACT:
      return '-';
    case GLFW_KEY_KP_DIVIDE:
      return '/';
    case GLFW_KEY_KP_MULTIPLY:
      return '*';
    case GLFW_KEY_KP_EQUAL:
      return '=';
    case GLFW_KEY_KP_DECIMAL:
      return '.';
    default:
      if ((glfwKey >= GLFW_KEY_SPACE && glfwKey <= GLFW_KEY_GRAVE_ACCENT)) {
        // These keys map to character codes directly
        if (!uppercase && glfwKey >= GLFW_KEY_A && glfwKey <= GLFW_KEY_Z) {
          // Make lowercase if needed
          return (char) (glfwKey + 32);
        }
        return (char) glfwKey;
      } else if (glfwKey >= GLFW_KEY_KP_0 && glfwKey <= GLFW_KEY_KP_9) {
        int offset = glfwKey - GLFW_KEY_KP_0;
        return (char) ('0' + offset);
      }
      // Can't convert to a meaningful character
      return Character.MAX_VALUE;
    }
  }


  private int convertKeyCode(int glfwKey) {
    switch (glfwKey) {
    case GLFW_KEY_UP:
      return PConstants.UP;
    case GLFW_KEY_DOWN:
      return PConstants.DOWN;
    case GLFW_KEY_LEFT:
      return PConstants.LEFT;
    case GLFW_KEY_RIGHT:
      return PConstants.RIGHT;
    case GLFW_KEY_LEFT_ALT:
    case GLFW_KEY_RIGHT_ALT:
      return PConstants.ALT;
    case GLFW_KEY_LEFT_CONTROL:
    case GLFW_KEY_RIGHT_CONTROL:
      return PConstants.CONTROL;
    case GLFW_KEY_LEFT_SHIFT:
    case GLFW_KEY_RIGHT_SHIFT:
      return PConstants.SHIFT;
    default:
      return glfwKey;
    }
  }


  private int convertMouseButton(int glfwButton) {
    switch (glfwButton) {
    case GLFW_MOUSE_BUTTON_LEFT:
      return PConstants.LEFT;
    case GLFW_MOUSE_BUTTON_RIGHT:
      return PConstants.RIGHT;
    case GLFW_MOUSE_BUTTON_MIDDLE:
      return PConstants.CENTER;
    default:
      return 0;
    }
  }

  private final List<Integer> pressedButtons = new ArrayList<>();

  private void updateMouseButtonCache(int button, boolean pressed) {
    // TODO: Check if this is needed, move to PApplet?
    if (pressed) { // put the button on the stack
      pressedButtons.add(button);
    } else { // remove the button from the stack
      int index = pressedButtons.lastIndexOf(button);
      if (index >= 0) {
        pressedButtons.remove(index);
      }
    }

    if (pressed) {
      pressedMouseButton = button; // TODO: check whether RELEASE event has the right key
    } else {
      if (pressedMouseButton == button) {
        // Released, select another the most recently pressed button or 0
        if (pressedButtons.isEmpty()) {
          pressedMouseButton = 0;
        } else {
          pressedMouseButton = pressedButtons.get(pressedButtons.size()-1);
        }
      }
    }
  }


  private void initInputListeners() {

    // mousePressed / mouseReleased / mouseClicked
    addWindowCallback(GLFW::glfwSetMouseButtonCallback, GLFWMouseButtonCallback
      .create((window1, button, action, mods) -> {
        modifiers = convertModifierBits(mods);
        button = convertMouseButton(button);

        // If running on Mac OS, allow ctrl-click as right mouse.
        // Verified to be necessary with Java 8u45.
        if (PApplet.platform == PConstants.MACOSX && button == PConstants.LEFT) {
          if (action == MouseEvent.PRESS && (modifiers & Event.CTRL) != 0) {
            macosxLeftButtonWithCtrlPressed = true;
          }
          if (macosxLeftButtonWithCtrlPressed) {
            button = PConstants.RIGHT;
          }
          if (action == GLFW_RELEASE) {
            macosxLeftButtonWithCtrlPressed = false;
          }
        }

        long nowMs = System.currentTimeMillis();
        switch (action) {
        case GLFW_PRESS:
          updateMouseButtonCache(button, true);
          scaledSketch.postMouseEvent(nowMs, MouseEvent.PRESS, modifiers,
                                      mouseX, mouseY, button, 0);
          // Click detection
          mousePressedX = mouseX;
          mousePressedY = mouseY;

          break;
        case GLFW_RELEASE:
          if (pgl.presentMode()) {
            int x = scaledSketch.windowToSketchUnits(mouseX);
            int y = scaledSketch.windowToSketchUnits(mouseY - monitorRect.h);
            if (pgl.insideStopButton(x, y)) {
              sketch.exit();
            }
          }
          updateMouseButtonCache(button, false);
          scaledSketch.postMouseEvent(nowMs, MouseEvent.RELEASE, modifiers,
                                      mouseX, mouseY, button, 0);

          // Detect mouse click
          int dragDist = PApplet.max(PApplet.abs(mouseX - mousePressedX),
                                     PApplet.abs(mouseY - mousePressedY));
          if (dragDist <= MOUSE_CLICK_CANCEL_DIST) {
            scaledSketch.postMouseEvent(nowMs, MouseEvent.CLICK, modifiers,
                                        mouseX, mouseY, button, 0);
          }

          break;
        }
      }));

    // mouseMoved / mouseDragged
    addWindowCallback(GLFW::glfwSetCursorPosCallback, GLFWCursorPosCallback
      .create((window1, xpos, ypos) -> {
        mouseX = (int) xpos;
        mouseY = (int) ypos;

        int action = pressedMouseButton == 0 ? MouseEvent.MOVE : MouseEvent.DRAG;
        long nowMs = System.currentTimeMillis();

        scaledSketch.postMouseEvent(nowMs, action, modifiers,
                                    mouseX, mouseY, pressedMouseButton, 0);

      }));

    // mouseWheel
    addWindowCallback(GLFW::glfwSetScrollCallback, GLFWScrollCallback
      .create((window1, xoffset, yoffset) -> {
        int count = (int) -yoffset;
        long nowMs = System.currentTimeMillis();
        scaledSketch.postMouseEvent(nowMs, MouseEvent.WHEEL, modifiers,
                                    mouseX, mouseY, pressedMouseButton, count);
      }));

    // mouseEntered / mouseExited
    addWindowCallback(GLFW::glfwSetCursorEnterCallback, GLFWCursorEnterCallback
      .create((window1, entered) -> {
        int action = entered ? MouseEvent.ENTER : MouseEvent.EXIT;
        long nowMs = System.currentTimeMillis();
        scaledSketch.postMouseEvent(nowMs, action, modifiers,
                                    mouseX, mouseY, pressedMouseButton, 0);
      }));

    // Make sure we receive LOCK keys in modifiers, so we can correctly
    // map to uppercase/lowercase based on CAPS LOCK state
    glfwSetInputMode(window, GLFW_LOCK_KEY_MODS, GLFW_TRUE);

    // keyPressed / keyReleased
    addWindowCallback(GLFW::glfwSetKeyCallback, GLFWKeyCallback
      .create((window1, key, scancode, action, mods) -> {
        modifiers = convertModifierBits(mods);

        boolean repeat = action == GLFW_REPEAT;

        if (action == GLFW_PRESS || action == GLFW_REPEAT) {
          action = KeyEvent.PRESS;
        } else if (action == GLFW_RELEASE) {
          action = KeyEvent.RELEASE;
        }

        boolean uppercase =
          ((mods & GLFW_MOD_SHIFT) != 0) ^ ((mods & GLFW_MOD_CAPS_LOCK) != 0);

        char pKey = convertKey(key, uppercase);
        int pKeyCode = convertKeyCode(key);

        long nowMs = System.currentTimeMillis();

        sketch.postEvent(new KeyEvent(null, nowMs,
                                      action, modifiers,
                                      pKey, pKeyCode, repeat));

        if (action == KeyEvent.PRESS && key == PConstants.ENTER) {
          sketch.postEvent(new KeyEvent(null, nowMs,
                                        KeyEvent.TYPE, modifiers,
                                        pKey, -1, false));
        }
      }));

    // keyTyped
    addWindowCallback(GLFW::glfwSetCharCallback, GLFWCharCallback
      .create((window1, codepoint) -> {

        long nowMs = System.currentTimeMillis();

        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
          codepoint = codepoint & 0xFF << 24 | codepoint & 0xFF00 << 8
            | codepoint & 0xFF0000 >> 8 | codepoint & 0xFF000000 >>> 24;
        }

        // Convert from UTF-32 to UTF-16
        // See: https://en.wikipedia.org/wiki/UTF-16#Description
        if ((codepoint & 0xFFFF0000) == 0) {
          // Single UTF-16 codepoint
          sketch.postEvent(new KeyEvent(null, nowMs,
                                        KeyEvent.TYPE, modifiers,
                                        (char) codepoint, -1, false));
        } else {
          // Two UTF-16 codepoints
          codepoint -= 0x10000;
          int high = 0xD800 + ((codepoint >> 22) & 0x3FF); // high 10 bits
          int low = 0xDC00 + (codepoint & 0x3FF); // low 10 bits
          sketch.postEvent(new KeyEvent(null, nowMs,
                                        KeyEvent.TYPE, modifiers,
                                        (char) high, -1, false));
          sketch.postEvent(new KeyEvent(null, nowMs,
                                        KeyEvent.TYPE, modifiers,
                                        (char) low, -1, false));
        }
      }));
  }

  //endregion


  @Override
  public void placeWindow(int[] location, int[] editorLocation) {
    if (sketch.sketchFullScreen()) {
      return;
    }

    // TODO: Guard against placing out of visible area might be needed on Windows [jv 2018-10-06]
    // TODO: Editor location might be negative if it is not on primary screen [jv 2018-10-06]
    // TODO: Editor location (from AWT) might be different than what LWJGL uses, check this [jv 2018-10-06]

    // - location parameter is the top left corner of the sketch frame (with decor)
    // - in GLFW location is the top left corner of the client area

    int cliW, cliH, frmL, frmT, frmW;
    try (MemoryStack stack = stackPush()) {
      IntBuffer cliWBuf = stack.mallocInt(1);
      IntBuffer cliHBuf = stack.mallocInt(1);
      IntBuffer frmLBuf = stack.mallocInt(1);
      IntBuffer frmTBuf = stack.mallocInt(1);
      IntBuffer frmRBuf = stack.mallocInt(1);

      glfwGetWindowSize(window, cliWBuf, cliHBuf);
      glfwGetWindowFrameSize(window, frmLBuf, frmTBuf, frmRBuf, null);

      cliW = cliWBuf.get(0);
      cliH = cliHBuf.get(0);
      frmL = frmLBuf.get(0);
      frmT = frmTBuf.get(0);
      frmW = cliWBuf.get(0) + frmLBuf.get(0) + frmRBuf.get(0);
    }

    if (location != null) {
      glfwSetWindowPos(window, location[0] + frmL, location[1] + frmT);
    } else if (editorLocation != null) {
      int locationX = editorLocation[0] - 20;
      int locationY = editorLocation[1];

      if (locationX - frmW > 10) {
        // if it fits to the left of the window
        glfwSetWindowPos(window, locationX - frmW + frmL, locationY + frmT);
      } else {
        // doesn't fit, center
        glfwSetWindowPos(window,
                         monitorRect.x + (monitorRect.w - cliW) / 2,
                         monitorRect.y + (monitorRect.h - cliH) / 2);
      }
    } else {
      // just center on screen
      glfwSetWindowPos(window,
                       monitorRect.x + (monitorRect.w - cliW) / 2,
                       monitorRect.y + (monitorRect.h - cliH) / 2);
    }
  }


  @Override
  public void placePresent(int stopColor) {
    int wuSketchWidth = scaledSketch.sketchToWindowUnits(sketch.sketchWidth());
    int wuSketchHeight = scaledSketch.sketchToWindowUnits(sketch.sketchHeight());
    float wuX = monitorRect.w - wuSketchWidth;
    float wuY = monitorRect.h - wuSketchHeight;
    float x = scaledSketch.windowToSketchUnits(wuX) * 0.5f;
    float y = scaledSketch.windowToSketchUnits(wuY) * 0.5f;
    pgl.initPresentMode(x, y, stopColor);
    glfwSetWindowMonitor(window, monitor, 0, 0, monitorRect.w, monitorRect.h, GLFW_DONT_CARE);
  }


  @Override
  public void setupExternalMessages() {
    external = true;
  }


  @Override
  public void setLocation(int x, int y) {
    if (sketch.sketchDisplay() == PConstants.SPAN) {
      // SPAN is a borderless window and moving it causes glitches
      return;
    }
    // TODO: Guard against placing out of visible area? [jv 2018-10-06]
    x = scaledSketch.sketchToWindowUnits(x);
    y = scaledSketch.sketchToWindowUnits(y);
    glfwSetWindowPos(window, x, y);
  }


  @Override
  public void setSize(int width, int height) {
    width = scaledSketch.sketchToWindowUnits(width);
    height = scaledSketch.sketchToWindowUnits(height);
    glfwSetWindowSize(window, width, height);
  }


  @Override
  public void setFrameRate(float fps) {
    if (fps < 1) {
      PGraphics.showWarning(
        "The OpenGL renderer cannot have a frame rate lower than 1.\n" +
          "Your sketch will run at 1 frame per second.");
      fps = 1;
    }
    this.frameRate = fps;

    // Limit the swap interval to 0 (V-Sync off) when the set framerate is
    // higher than the refresh rate of the screen, or 1 (V-Sync on) when the
    // set framerate is less than or equal to the refres hrate of the screen.
    // Waiting for more than one frame before swapping is usually not what
    // anybody wants. We limit the framerate manually which gives us more
    // control.
    this.swapInterval = Math.min(1, (int) (this.monitorRefreshRate / fps));
    this.swapIntervalChanged = true;
  }


  @Override
  public void setCursor(int kind) {
    switch (kind) {
    case PConstants.ARROW:
      kind = GLFW_ARROW_CURSOR;
      break;
    case PConstants.CROSS:
      kind = GLFW_CROSSHAIR_CURSOR;
      break;
    case PConstants.HAND:
      kind = GLFW_HAND_CURSOR;
      break;
    case PConstants.TEXT:
      kind = GLFW_IBEAM_CURSOR;
      break;
    case PConstants.MOVE:
      // TODO: GLFW does not have move, only horizontal or vertical resize [jv 2018-10-06]
      kind = GLFW_HRESIZE_CURSOR;
      break;
    case PConstants.WAIT:
      kind = GLFW_ARROW_CURSOR;
      PGraphics.showWarning("This renderer does not support WAIT cursor.");
      break;
    default:
      PGraphics.showWarning("Unknown cursor kind.");
      return;
    }
    glfwCreateStandardCursor(kind);
    glfwSetCursor(window, kind);
  }


  @Override
  public void setCursor(PImage image, int hotspotX, int hotspotY) {
    // TODO: custom cursors
  }


  @Override
  public void showCursor() {
    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
  }


  @Override
  public void hideCursor() {
    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
  }

  private void setupDebugOpenGLCallback() {
    if (GL.getCapabilities().GL_KHR_debug) {
      GL21C.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
      GL21C.glEnable(KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS);
      debugCallback = GLDebugMessageCallback
          .create((source, type, id, severity, length, message, userParam) -> {
//            if (type == KHRDebug.GL_DEBUG_TYPE_ERROR ||
//                type == KHRDebug.GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR ||
//                type == KHRDebug.GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR ||
//                type == KHRDebug.GL_DEBUG_TYPE_PORTABILITY) {
//            }
            new Exception(MemoryUtil.memUTF8(message)).printStackTrace();
          });
      KHRDebug.glDebugMessageCallback(debugCallback, 0);
    }
  }

  @Override
  public void startThread() {

    // TODO: Move OpenGL loop to another thread?
    // Adds a lot of complexity, but does not block event queue

    glfwMakeContextCurrent(window);

    GL.createCapabilities();
    pgl.setThread(Thread.currentThread());

    if (DEBUG_GLFW) {
      setupDebugOpenGLCallback();
    }

    this.threadRunning = true;

    while (this.threadRunning) {

      // Set the swap interval after the setup() to give the user a chance to
      // disable V-Sync. As GLFW docs for glfwSwapInterval(int) mention,
      // "(...) some swap interval extensions used by GLFW do not allow the swap
      // interval to be reset to zero once it has been set to a non-zero value."
      if (sketch.frameCount > 0 && this.swapIntervalChanged) {
        glfwSwapInterval(this.swapInterval);
        this.swapIntervalChanged = false;
      }

      // Limit the framerate
      Sync.sync(frameRate);

      glfwPollEvents();

      handleDraw();
    }

    // Need to clean up before exiting
    // TODO: Make sure sketch does not System.exits before this could run, e.g. during noLoop()
    glfwDestroyWindow(window);
    glfwTerminate();

    sketch.exitActual();
  }


  /**
   * Calls the sketch handleDraw() method and swaps the buffers if the frame was
   * drawn. It is in this separate method so that it can be called from redraw
   * callback when the main loop might be paused.
   */
  private void handleDraw() {
    if (sketch.frameCount == 0 && requestedSketchSize != null) {
      if (windowPosSize.w < requestedSketchSize.w || windowPosSize.h < requestedSketchSize.h) {
        PGraphics.showWarning("The sketch has been automatically resized to fit the screen resolution");
      }
    }

    if (!sketch.finished) {
      int pframeCount = sketch.frameCount;
      sketch.handleDraw();
      if (pframeCount != sketch.frameCount && !sketch.finished) {
        // Swap buffers only if drawing happened
        glfwSwapBuffers(window);
      }
      // TODO: PGraphicsOpenGL.completeFinishedPixelTransfers();
    }

    if (sketch.exitCalled()) {
      // TODO: PGraphicsOpenGL.completeAllPixelTransfers();
      sketch.dispose(); // calls stopThread(), which stops the animator.
    }
  }


  @Override
  public void pauseThread() {
    // TODO: Implement if we move rendering to a separate thread [jv 2018-10-06]
  }


  @Override
  public void resumeThread() {
    // TODO: Implement if we move rendering to a separate thread [jv 2018-10-06]
  }


  @Override
  public boolean stopThread() {
    boolean ret = threadRunning;
    threadRunning = false;
    return ret;
  }


  @Override
  public boolean isStopped() {
    return !threadRunning;
  }


  /**
   * Helper for PApplet DPI scaling. It has methods for converting between
   * sketch units and window units, posting mouse events with scaled position,
   * and updating sketch size when the window is resized or moved to a monitor
   * with a different content scale.
   */
  private static class ScaledSketch {

    private final PApplet sketch;
    private final PGraphics graphics;
    private final PGL pgl;

    /**
     * True if user called <tt>pixelDensity(2)</tt> and thus is aware that
     * the sketch size might not be equal to the pixel size.
     */
    private final boolean isSketchDensityAware;
    private int sketchDensity;

    /**
     * Some window systems, like Win32 or X11, have window units always equal
     * pixels, even if content scale is not 1. This boolean is true if we are
     * running on one of these window systems.
     */
    private final boolean hasWindowUnitsEqualToPixels;

    /**
     * Keeps track of the scaling factor between window units and screen pixels.
     *
     * <pre>windowUnits * factor = pixels</pre>
     */
    private float windowUnitsToPixelsFactor;

    ScaledSketch(PApplet sketch, PGraphics graphics, float contentScale) {
      this.sketch = sketch;
      this.graphics = graphics;
      this.pgl = ((PGraphicsLWJGL) graphics).pgl;

      // If this is 2, user is aware that the sketch size might not be equal to
      // the pixel size.
      this.isSketchDensityAware = sketch.sketchPixelDensity() > 1;

      { // Check whether window coords map to pixels 1:1
        // Parse the window system from the version string, e.g.:
        // 3.3.0 Win32 WGL EGL VisualC DLL
        // <version> <window system> <other info>
        // Win32 and X11 are 1:1, others not.
        String version = glfwGetVersionString();
        String[] parts = version.split(" ", 3);
        boolean isWin32orX11 = false;
        if (parts.length >= 2) {
          isWin32orX11 = parts[1].equalsIgnoreCase("Win32") ||
              parts[1].equalsIgnoreCase("X11");
        }
        hasWindowUnitsEqualToPixels = isWin32orX11;
      }

      // Run these after the window system check above!
      this.windowUnitsToPixelsFactor = calculateNewWindowUnitsToPixelsFactor(contentScale);
      this.sketchDensity = calculateNewSketchDensity(contentScale);
    }


    /**
     * Calculates new window to pixel coords factor from window content scale.
     * @param contentScale window content scale reported by the window system
     * @return 1.0f on window systems where window coords map 1:1 to pixels
     * (Win32, X11), content scale otherwise
     */
    private float calculateNewWindowUnitsToPixelsFactor(float contentScale) {
      return hasWindowUnitsEqualToPixels ? 1.0f : contentScale;
    }

    /**
     * Calculates new sketch density from window content scale.
     * @param contentScale window content scale reported by the window system
     * @return 1 if the sketch is not density aware, content scale rounded
     * down to nearest integer otherwise
     */
    private int calculateNewSketchDensity(float contentScale) {
      if (!isSketchDensityAware) {
        return 1;
      }
      // Round fractional scales down to prevent blurriness
      int contentScaleInt = PApplet.floor(contentScale);
      return PApplet.max(contentScaleInt, 1);
    }


    /**
     * Notify the sketch that framebuffer size or content scale changed.
     * Updates pixel-to-window ratio and sketch density.
     *
     * @param contentScale window content scale reported by the window system
     * @param pixelWidth framebuffer width reported by the window system
     * @param pixelHeight framebuffer height reported by the window system
     */
    void updateSketchSize(float contentScale, int pixelWidth, int pixelHeight) {
      this.windowUnitsToPixelsFactor = calculateNewWindowUnitsToPixelsFactor(contentScale);
      this.sketchDensity = calculateNewSketchDensity(contentScale);

      // Make sure sketchWidth * sketchDensity >= pixelWidth, same for height
      int sketchWidth = (pixelWidth + sketchDensity - 1) / sketchDensity;
      int sketchHeight = (pixelHeight + sketchDensity - 1) / sketchDensity;

      // Resize FBO Layer (if present) - needed for noLoop() sketches
      pgl.resetFBOLayer();

      sketch.pixelDensity = sketchDensity;
      sketch.setSize(sketchWidth, sketchHeight);
      graphics.pixelDensity = sketchDensity;
      graphics.setSize(sketchWidth, sketchHeight);
    }


    /**
     * Converts a position/size from sketch units to window units.
     * @param sketchUnits size/position in sketch units
     * @return size/position in window units
     */
    int sketchToWindowUnits(int sketchUnits) {
      return PApplet.floor(sketchUnits * sketchDensity / windowUnitsToPixelsFactor);
    }


    /**
     * Converts a position/size from window units to sketch units.
     * @param windowUnits size/position in window units
     * @return size/position in sketch units
     */
    int windowToSketchUnits(float windowUnits) {
      return PApplet.floor(windowUnits * windowUnitsToPixelsFactor / sketchDensity);
    }


    /**
     * Helper method which converts the mouse position form window units to
     * sketch units and posts the mouse event to he sketch input event queue.
     * All params match the MouseEvent constructor.
     *
     * @see MouseEvent#MouseEvent(Object, long, int, int, int, int, int, int)
     */
    void postMouseEvent(long millis, int action, int modifiers,
                        int windowX, int windowY, int button, int count) {
      int sketchX = windowToSketchUnits(windowX);
      int sketchY = windowToSketchUnits(windowY);
      if (pgl.presentMode()) {
        sketchX -= pgl.presentX;
        sketchY -= pgl.presentY;
        if (sketchX < 0 || sketchX >= sketch.sketchWidth() ||
            sketchY < 0 || sketchY >= sketch.sketchHeight()) {
          return;
        }
      }
      // TODO: Synthesize native event object? All data is passed through,
      //       so there is probably no benefit in doing so [jv 2018-11-07]
      MouseEvent event = new MouseEvent(null,
                                        millis, action, modifiers,
                                        sketchX, sketchY, button, count);
      sketch.postEvent(event);
    }

  }


  /**
   * Represents a rectangle as a top left corner plus width and height.
   */
  private static class Rectangle {
    int x, y, w, h;
    Rectangle() {}
    Rectangle(int x, int y, int w, int h) {
      this.x = x;
      this.y = y;
      this.w = w;
      this.h = h;
    }
    @Override
    public String toString() {
      return "Rectangle{" + "x=" + x + ", y=" + y + ", w=" + w + ", h=" + h
        + '}';
    }
  }


  /**
   * A highly accurate sync method that continually adapts to the system
   * it runs on to provide reliable results.
   *
   * @author Riven
   * @author kappaOne
   */
  private static class Sync {

    /*
     * Copyright (c) 2002-2012 LWJGL Project
     * All rights reserved.
     *
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are
     * met:
     *
     * * Redistributions of source code must retain the above copyright
     *   notice, this list of conditions and the following disclaimer.
     *
     * * Redistributions in binary form must reproduce the above copyright
     *   notice, this list of conditions and the following disclaimer in the
     *   documentation and/or other materials provided with the distribution.
     *
     * * Neither the name of 'LWJGL' nor the names of
     *   its contributors may be used to endorse or promote products derived
     *   from this software without specific prior written permission.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
     * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
     * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
     * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
     * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
     * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
     * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
     * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
     * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
     * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
     * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
     */

    /** number of nano seconds in a second */
    private static final long NANOS_IN_SECOND = 1000L * 1000L * 1000L;

    /** The time to sleep/yield until the next frame */
    private static long nextFrame = 0;

    /** whether the initialisation code has run */
    private static boolean initialised = false;

    /** for calculating the averages the previous sleep/yield times are stored */
    private static RunningAvg sleepDurations = new RunningAvg(10);
    private static RunningAvg yieldDurations = new RunningAvg(10);


    /**
     * An accurate sync method that will attempt to run at a constant frame rate.
     * It should be called once every frame.
     *
     * @param fps - the desired frame rate, in frames per second
     */
    public static void sync(float fps) {
      if (fps <= 0) return;
      if (!initialised) initialise();

      try {
        // sleep until the average sleep time is greater than the time remaining till nextFrame
        for (long t0 = getTime(), t1; (nextFrame - t0) > sleepDurations.avg(); t0 = t1) {
          Thread.sleep(1);
          sleepDurations.add((t1 = getTime()) - t0); // update average sleep time
        }

        // slowly dampen sleep average if too high to avoid yielding too much
        sleepDurations.dampenForLowResTicker();

        // yield until the average yield time is greater than the time remaining till nextFrame
        for (long t0 = getTime(), t1; (nextFrame - t0) > yieldDurations.avg(); t0 = t1) {
          Thread.yield();
          yieldDurations.add((t1 = getTime()) - t0); // update average yield time
        }
      } catch (InterruptedException e) {

      }

      // schedule next frame, drop frame(s) if already too late for next frame
      nextFrame = Math.max(nextFrame + (long) (NANOS_IN_SECOND / (double) fps), getTime());
    }

    /**
     * This method will initialise the sync method by setting initial
     * values for sleepDurations/yieldDurations and nextFrame.
     *
     * If running on windows it will start the sleep timer fix.
     */
    private static void initialise() {
      initialised = true;

      sleepDurations.init(1000 * 1000);
      yieldDurations.init((int) (-(getTime() - getTime()) * 1.333));

      nextFrame = getTime();

      if (PApplet.platform == PConstants.WINDOWS) {
        // On windows the sleep functions can be highly inaccurate by
        // over 10ms making in unusable. However it can be forced to
        // be a bit more accurate by running a separate sleeping daemon
        // thread.
        Thread timerAccuracyThread = new Thread(() -> {
          try {
            Thread.sleep(Long.MAX_VALUE);
          } catch (Exception e) {}
        });

        timerAccuracyThread.setName("LWJGL Timer");
        timerAccuracyThread.setDaemon(true);
        timerAccuracyThread.start();
      }
    }

    /**
     * Get the system time in nano seconds
     *
     * @return will return the current time in nano's
     */
    private static long getTime() {
      return (glfwGetTimerValue() * NANOS_IN_SECOND) / glfwGetTimerFrequency();
    }

    private static class RunningAvg {
      private final long[] slots;
      private int offset;

      private static final long DAMPEN_THRESHOLD = 10 * 1000L * 1000L; // 10ms
      private static final float DAMPEN_FACTOR = 0.9f; // don't change: 0.9f is exactly right!

      public RunningAvg(int slotCount) {
        this.slots = new long[slotCount];
        this.offset = 0;
      }

      public void init(long value) {
        while (this.offset < this.slots.length) {
          this.slots[this.offset++] = value;
        }
      }

      public void add(long value) {
        this.slots[this.offset++ % this.slots.length] = value;
        this.offset %= this.slots.length;
      }

      public long avg() {
        long sum = 0;
        for (int i = 0; i < this.slots.length; i++) {
          sum += this.slots[i];
        }
        return sum / this.slots.length;
      }

      public void dampenForLowResTicker() {
        if (this.avg() > DAMPEN_THRESHOLD) {
          for (int i = 0; i < this.slots.length; i++) {
            this.slots[i] *= DAMPEN_FACTOR;
          }
        }
      }
    }
  }

}
