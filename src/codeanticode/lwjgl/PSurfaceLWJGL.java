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

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_TRUE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.glfw.GLFW.*;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.lang.reflect.Field;

import org.lwjgl.BufferUtils;
//import org.lwjgl.LWJGLException;
//import org.lwjgl.input.Cursor;
//import org.lwjgl.input.Keyboard;
//import org.lwjgl.input.Mouse;
//import org.lwjgl.opengl.Display;
//import org.lwjgl.opengl.DisplayMode;
//import org.lwjgl.opengl.PixelFormat;

import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
//import org.lwjgl.glfw.GLFWvidmode;
import org.lwjgl.opengl.GL11;
//import org.lwjgl.opengl.GLContext;
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
import processing.opengl.PGraphicsOpenGL;

public class PSurfaceLWJGL implements PSurface {
  GraphicsDevice displayDevice;
  PApplet sketch;
  PGraphics graphics;

  public int sketchWidth;
  public int sketchHeight;
  public int fbWidth;
  public int fbHeight;
  

  Frame frame;
  // Note that x and y may not be zero, depending on the display configuration
  Rectangle screenRect;

  PLWJGL pgl;

  private GLFWMouseButtonCallback mouseCallback;
  private GLFWCursorPosCallback posCallback;
  private GLFWKeyCallback keyCallback;
  private GLFWScrollCallback scrollCallback;
  private GLFWFramebufferSizeCallback fbCallback;
  private GLFWErrorCallback errorCallback;
  //The window handle
  private long window;

  int cursorType = PConstants.ARROW; // cursor type
  boolean cursorVisible = true; // cursor visibility flag
//  Cursor invisibleCursor;
//  Cursor currentCursor;

  protected float[] currentPixelScale = {0, 0};

  // ........................................................

  // Event handling

  boolean externalMessages = false;

  /** Poller threads to get the keyboard/mouse events from LWJGL */
//  protected static KeyPoller keyPoller;
//  protected static MousePoller mousePoller;

  Thread thread;
  boolean paused;
  Object pauseObject = new Object();

  /** As of release 0116, frameRate(60) is called as a default */
  protected float frameRateTarget = 60;


  PSurfaceLWJGL(PGraphics graphics) {
    this.graphics = graphics;
    this.pgl = (PLWJGL) ((PGraphicsOpenGL)graphics).pgl;
  }

  public Object getNative() {
    // TODO Auto-generated method stub
    return null;
  }


  public void setAlwaysOnTop(boolean always) {
    // TODO Auto-generated method stub

  }


  public void setIcon(PImage icon) {
    // TODO Auto-generated method stub

  }


  public void setLocation(int x, int y) {
    // TODO Auto-generated method stub

  }


  public void initOffscreen(PApplet sketch) {
    this.sketch = sketch;

    sketchWidth = sketch.sketchWidth();
    sketchHeight = sketch.sketchHeight();
    
    fbWidth = sketchWidth;
    fbHeight = sketchHeight;
  }
  
  @Override
  public void initFrame(PApplet sketch) {
//    , int backgroundColor,
//    int deviceIndex, boolean fullScreen, boolean spanDisplays

    this.sketch = sketch;
    sketchWidth = sketch.sketchWidth();
    sketchHeight = sketch.sketchHeight();
    fbWidth = sketchWidth;
    fbHeight = sketchHeight;
  }


  // get the bounds for all displays
  static Rectangle getDisplaySpan() {
    Rectangle bounds = new Rectangle();
    GraphicsEnvironment environment =
      GraphicsEnvironment.getLocalGraphicsEnvironment();
    for (GraphicsDevice device : environment.getScreenDevices()) {
      for (GraphicsConfiguration config : device.getConfigurations()) {
        Rectangle2D.union(bounds, config.getBounds(), bounds);
      }
    }
    return bounds;
  }


  @Override
  public void setTitle(String title) {
    if (window == 0) return;
    glfwSetWindowTitle(window, title);
  }


  @Override
  public void setVisible(boolean visible) {
    if (window == 0) return;
    if (visible) {
      glfwShowWindow(window);
    } else {
      glfwHideWindow(window);
    }
  }


  @Override
  public void setResizable(boolean resizable) {
    if (resizable) {
      glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
    } else {
      glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
    }
  }


  @Override
  public void placeWindow(int[] location, int[] editorLocation) {
    if (window == 0) return;
    if (sketch.sketchFullScreen()) return;

    if (location != null) {
      glfwSetWindowPos(window, location[0], location[1]);      
    } else if (editorLocation != null) {      
      int locationX = editorLocation[0] - 20;
      int locationY = editorLocation[1];
      
      int w = sketchWidth;
      int h = sketchHeight;

      if (locationX - w > 10) {
        // if it fits to the left of the window
        glfwSetWindowPos(window, locationX - w, locationY); 
      } else {  // doesn't fit
        locationX = (sketch.displayWidth - w) / 2;
        locationY = (sketch.displayHeight - h) / 2;
        glfwSetWindowPos(window, locationX, locationY);         
      }
      
    } else {  // just center on screen
      try ( MemoryStack stack = stackPush() ) {
        IntBuffer pWidth = stack.mallocInt(1); // int*
        IntBuffer pHeight = stack.mallocInt(1); // int*

        // Get the window size passed to glfwCreateWindow
        glfwGetWindowSize(window, pWidth, pHeight);

        // Get the resolution of the primary monitor
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

        // Center the window
        glfwSetWindowPos(window, (vidmode.width() - pWidth.get(0)) / 2, (vidmode.height() - pHeight.get(0)) / 2);               
      }
    }
  }


//  boolean presentMode = false;
//  float offsetX;
//  float offsetY;
  @Override
  public void placePresent(int stopColor) {
    float scale = getPixelScale();
    pgl.initPresentMode(0.5f * (screenRect.width/scale - sketchWidth),
                        0.5f * (screenRect.height/scale - sketchHeight), stopColor);
    PApplet.hideMenuBar();
  }


  @Override
  public void setupExternalMessages() {
    externalMessages = true;
  }

  /*
  private void setFrameCentered() {

    // Can't use frame.setLocationRelativeTo(null) because it sends the
    // frame to the main display, which undermines the --display setting.
    Display.setLocation(screenRect.x + (screenRect.width - sketchWidth) / 2,
                        screenRect.y + (screenRect.height - sketchHeight) / 2);
  }
*/


  @Override
  public void startThread() {
    
    // Put LWJGL's init and loop temporarily here:
    init();    
    loop();

    /*
    if (thread == null) {
      thread = new AnimationThread();
      thread.start();
    } else {
      throw new IllegalStateException("Thread already started in PSurfaceLWJGL");
    }
    */
  }

  protected void init() {
    int WIDTH = sketchWidth;
    int HEIGHT = sketchHeight;
    fbWidth = sketchWidth;
    fbHeight = sketchHeight;
    
    // Setup an error callback. The default implementation
    // will print the error message in System.err.
    GLFWErrorCallback.createPrint(System.err).set();

    // Initialize GLFW. Most GLFW functions will not work before doing this.
    if (!glfwInit()) {
      throw new IllegalStateException("Unable to initialize GLFW");
    }

    // Configure GLFW
    glfwDefaultWindowHints(); // optional, the current window hints are already the default
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE); // the window will not be resizable

    // Create the window
    window = glfwCreateWindow(WIDTH, HEIGHT, "Hello World!", NULL, NULL);    
    if (window == NULL) {
      throw new RuntimeException("Failed to create the GLFW window");
    }

    

    // Event handling in LWJGL3:
    // https://github.com/LWJGL/lwjgl3-wiki/wiki/2.6.3-Input-handling-with-GLFW
    registerKeyEvents();
    registerMouseEvents();
    
    registerFBEvents();

    // Place window initially, should use placeWindow()  
    // Get the thread stack and push a new frame
    try (MemoryStack stack = stackPush()) {
      IntBuffer pWidth = stack.mallocInt(1); // int*
      IntBuffer pHeight = stack.mallocInt(1); // int*

      // Get the window size passed to glfwCreateWindow
      glfwGetWindowSize(window, pWidth, pHeight);

      // Get the resolution of the primary monitor
      GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

      int px = (vidmode.width() - pWidth.get(0)) / 2;
      int py = (vidmode.height() - pHeight.get(0)) / 2;
      
      // Center the window
      glfwSetWindowPos(window, px, py);
    } // the stack frame is popped automatically    
    
    // Make the OpenGL context current
    glfwMakeContextCurrent(window);
    // Enable v-sync
    glfwSwapInterval(1);

    // Make the window visible
    glfwShowWindow(window);    
    
    GL.createCapabilities();
  }
 
  protected void registerKeyEvents() {
    // Setup a key callback. It will be called every time a key is pressed, repeated or released.
//    glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
//      if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
//        glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
//    });

    
    glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
      @Override
      public void invoke(long window, int key, int scancode, int action,
          int mods) {
        System.out.println("Key pressed: " + key + ", " + scancode + " " + action);
        if (action != GLFW_RELEASE) return;
        if (key == GLFW_KEY_ESCAPE) {
          glfwSetWindowShouldClose(window, true);
        }
      }
    });    
  }
  
  protected void registerMouseEvents() {
    glfwSetMouseButtonCallback(window, mouseCallback = new GLFWMouseButtonCallback() {
      @Override
      public void invoke(long window, int button, int action, int mods) {
        System.out.println("mouse pressed: " + button + ", " + action + " " + mods);
      }
    });

    
//    glfwSetCursorPosCallback(window, (window, x, y) -> {
//      System.out.println("cursor moved " + x + ", " + y);      
//    }); 
    
  
    glfwSetCursorPosCallback(window, posCallback = new GLFWCursorPosCallback() {
      @Override
      public void invoke(long window, double xpos, double ypos) {
        System.out.println("mouse moved: " + xpos + " " + ypos);


        long millis = System.currentTimeMillis();

        int modifiers = 0;
//        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ||
//            Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
//          modifiers |= Event.SHIFT;
//        }
//        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) ||
//            Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
//          modifiers |= Event.CTRL;
//        }
//        if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) ||
//            Keyboard.isKeyDown(Keyboard.KEY_RMETA)) {
//          modifiers |= Event.META;
//        }
//        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) ||
//            Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
//          // LWJGL maps the menu key and the alt key to the same value.
//          modifiers |= Event.ALT;
//        }

        int x = (int)xpos;
        int y = (int)ypos;
        int button = 0;

        int action = 0;
        action = MouseEvent.MOVE;
//
        int count = 0;

        MouseEvent me = new MouseEvent(null, millis, action, modifiers,
                                       x, y, button, count);
        sketch.postEvent(me);
      }
    });

    glfwSetScrollCallback(window, scrollCallback = new GLFWScrollCallback() {
      @Override
      public void invoke(long window, double xoffset, double yoffset) {
        System.out.println("mouse scrolled: " + xoffset + " " + yoffset);
      }
    });

  }
  
  protected void registerFBEvents() {
    /*
     * We need to get notified when the GLFW window framebuffer size changed (i.e.
     * by resizing the window), in order to recreate our own ray tracer framebuffer
     * texture.
     */
    glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
      public void invoke(long window, int width, int height) {
        System.out.println(width + "x" + height);
        fbWidth = width;
        fbHeight = height;        
      }
    });    
    
    /*
     * Account for HiDPI screens where window size != framebuffer pixel size.
     */
    try (MemoryStack frame = MemoryStack.stackPush()) {
      IntBuffer framebufferSize = frame.mallocInt(2);
      nglfwGetFramebufferSize(window, MemoryUtil.memAddress(framebufferSize), MemoryUtil.memAddress(framebufferSize) + 4);
      fbWidth = framebufferSize.get(0);
      fbHeight = framebufferSize.get(1);
    }    
  }
  
  protected void loop() {

    // Run the rendering loop until the user has attempted to close
    // the window or has pressed the ESCAPE key.
    // Cannot put this inside a thread
    while ( !glfwWindowShouldClose(window) ) {
//      glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer
      pgl.setThread(Thread.currentThread());
      
      sketch.handleDraw();

      glfwSwapBuffers(window); // swap the color buffers

      // Poll for window events. The key callback above will only be
      // invoked during this call.
      glfwPollEvents();
    }
      
  }
  

  @Override
  public void pauseThread() {
    PApplet.debug("PApplet.run() paused, calling object wait...");
    paused = true;
  }


  // halts the animation thread if the pause flag is set
  protected void checkPause() {
    if (paused) {
      synchronized (pauseObject) {
        try {
          pauseObject.wait();
//          PApplet.debug("out of wait");
        } catch (InterruptedException e) {
          // waiting for this interrupt on a start() (resume) call
        }
      }
    }
//    PApplet.debug("done with pause");
  }


  @Override
  public void resumeThread() {
    paused = false;
    synchronized (pauseObject) {
      pauseObject.notifyAll();  // wake up the animation thread
    }
  }


  @Override
  public boolean stopThread() {
    if (thread == null) {
      return false;
    }
    thread = null;
    return true;
  }


  @Override
  public boolean isStopped() {
    return thread == null;
  }


  @Override
  public void setSize(int width, int height) {
    if (frame != null) {
      sketchWidth = sketch.width = width;
      sketchHeight = sketch.height = height;
      graphics.setSize(width, height);
    }
  }



  public float getPixelScale() {
    if (graphics.pixelDensity == 1) {
      return 1;
    }

    if (PApplet.platform == PConstants.MACOSX) {
      return getCurrentPixelScale();
    }

    return 2;
  }

  private float getCurrentPixelScale() {
    // Even if the graphics are retina, the user might have moved the window
    // into a non-retina monitor, so we need to check
//    window.getCurrentSurfaceScale(currentPixelScale);
    return currentPixelScale[0];
  }



//  @Override
//  public void initImage(PGraphics graphics) {
//    // TODO not sure yet how to implement [fry]
//  }
  public Component getComponent() {
    return frame;
  }


  public void setSmooth(int level) {
    System.err.println("set smooth " + level);
    pgl.reqNumSamples = level;
  }


  @Override
  public void setFrameRate(float fps) {
    /*
    frameRateTarget = fps;
    if (60 < fps) {
      // Disables v-sync
      System.err.println("Disabling VSync");
      Display.setVSyncEnabled(false);
    } else  {
      Display.setVSyncEnabled(true);
    }
    */
  }


  public void requestFocus() {
    // seems there is no way of request focus on the LWJGL Display, unless
    // it is parented inside a Canvas:
    // http://www.java-gaming.org/index.php?topic=31158.0
  }


//  @Override
//  public void blit() {
//    // Nothing to do here
//  }


  @Override
  public void setCursor(int kind) {
    System.err.println("Cursor types not supported in OpenGL, provide your cursor image");
  }


  @Override
  public void setCursor(PImage image, int hotspotX, int hotspotY) {
    /*
    BufferedImage jimg = (BufferedImage)image.getNative();
    IntBuffer buf = IntBuffer.wrap(jimg.getRGB(0, 0, jimg.getWidth(), jimg.getHeight(),
                                               null, 0, jimg.getWidth()));
    try {
      currentCursor = new Cursor(jimg.getWidth(), jimg.getHeight(),
                                 hotspotX, hotspotY, 1, buf, null);
      Mouse.setNativeCursor(currentCursor);
      cursorVisible = true;
    } catch (LWJGLException e) {
      e.printStackTrace();
    }
    */
  }


  @Override
  public void showCursor() {
    if (!cursorVisible) {
      /*
      try {
        Mouse.setNativeCursor(currentCursor);
        cursorVisible = true;
      } catch (LWJGLException e) {
        e.printStackTrace();
      }
      */
    }
  }


  @Override
  public void hideCursor() {
    /*
    if (invisibleCursor == null) {
      try {
        invisibleCursor = new Cursor(1, 1, 0, 0, 1, BufferUtils.createIntBuffer(1), null);
      } catch (LWJGLException e1) {
        e1.printStackTrace();
      }
    }
    try {
      Mouse.setNativeCursor(invisibleCursor);
      cursorVisible = false;
    } catch (LWJGLException e) {
      e.printStackTrace();
    }
    */
  }


  class AnimationThread extends Thread {
    public AnimationThread() {
      super("Animation Thread");
    }

    /**
     * Main method for the primary animation thread.
     * <A HREF="http://java.sun.com/products/jfc/tsc/articles/painting/">Painting in AWT and Swing</A>
     */
    @Override
    public void run() {  // not good to make this synchronized, locks things up

/*
      int WIDTH = sketchWidth;
      int HEIGHT = sketchHeight;



      // Create the window
      window = glfwCreateWindow(WIDTH, HEIGHT, "Hello World!", NULL, NULL);
      if (window == NULL)
        throw new RuntimeException("Failed to create the GLFW window");




      glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
        @Override
        public void invoke(long window, int key, int scancode, int action,
            int mods) {
          System.out.println("Key pressed: " + key + ", " + scancode + " " + action);
          if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
            glfwSetWindowShouldClose(window, true); // We will detect this in
                                                       // our rendering loop
        }
      });

      glfwSetMouseButtonCallback(window, mouseCallback = new GLFWMouseButtonCallback() {
        @Override
        public void invoke(long window, int button, int action, int mods) {
          System.out.println("mouse pressed: " + button + ", " + action + " " + mods);
        }
      });

      glfwSetCursorPosCallback(window, posCallback = new GLFWCursorPosCallback() {
        @Override
        public void invoke(long window, double xpos, double ypos) {
          System.out.println("mouse moved: " + xpos + " " + ypos);


          long millis = System.currentTimeMillis();

          int modifiers = 0;
//          if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ||
//              Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
//            modifiers |= Event.SHIFT;
//          }
//          if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) ||
//              Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
//            modifiers |= Event.CTRL;
//          }
//          if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) ||
//              Keyboard.isKeyDown(Keyboard.KEY_RMETA)) {
//            modifiers |= Event.META;
//          }
//          if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) ||
//              Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
//            // LWJGL maps the menu key and the alt key to the same value.
//            modifiers |= Event.ALT;
//          }

          int x = (int)xpos;
          int y = (int)ypos;
          int button = 0;
//          if (Mouse.isButtonDown(0)) {
//            button = PConstants.LEFT;
//          } else if (Mouse.isButtonDown(1)) {
//            button = PConstants.RIGHT;
//          } else if (Mouse.isButtonDown(2)) {
//            button = PConstants.CENTER;
//          }

          int action = 0;
//          if (button != 0) {
//            if (pressed) {
//              action = MouseEvent.DRAG;
//            } else {
//              action = MouseEvent.PRESS;
//              pressed = true;
//            }
//          } else if (pressed) {
//            action = MouseEvent.RELEASE;
//          } else {
//            action = MouseEvent.MOVE;
//          }
          action = MouseEvent.MOVE;

//          if (inside) {
//            if (!Mouse.isInsideWindow()) {
//              inside = false;
//              action = MouseEvent.EXIT;
//            }
//          } else {
//            if (Mouse.isInsideWindow()) {
//              inside = true;
//              action = MouseEvent.ENTER;
//            }
//          }
//
          int count = 0;
//          if (Mouse.getEventButtonState()) {
//            startedClickTime = millis;
//            startedClickButton = button;
//          } else {
//            if (action == MouseEvent.RELEASE) {
//              boolean clickDetected = millis - startedClickTime < 500;
//              if (clickDetected) {
//                // post a RELEASE event, in addition to the CLICK event.
//                MouseEvent me = new MouseEvent(null, millis, action, modifiers,
//                                               x, y, button, count);
//                parent.postEvent(me);
//                action = MouseEvent.CLICK;
//                count = 1;
//              }
//            }
//          }



          MouseEvent me = new MouseEvent(null, millis, action, modifiers,
                                         x, y, button, count);
          sketch.postEvent(me);


        }
      });

      glfwSetScrollCallback(window, scrollCallback = new GLFWScrollCallback() {
        @Override
        public void invoke(long window, double xoffset, double yoffset) {
          System.out.println("mouse scrolled: " + xoffset + " " + yoffset);
        }
      });



      // Get the resolution of the primary monitor
      long mon = glfwGetPrimaryMonitor();
      GLFWVidMode vidmode = glfwGetVideoMode(mon);
      // Center our window
      glfwSetWindowPos(window, (vidmode.width() - WIDTH) / 2,
                               (vidmode.height() - HEIGHT) / 2);

      // Make the OpenGL context current
      glfwMakeContextCurrent(window);
      // Enable v-sync
      glfwSwapInterval(1);

      // Make the window visible
      glfwShowWindow(window);


//      GLContext.createFromCurrent();

      // Set the clear color
      glClearColor(1.0f, 0.0f, 0.0f, 0.0f);

      // Run the rendering loop until the user has attempted to close
      // the window or has pressed the ESCAPE key.
      while (!glfwWindowShouldClose(window)) {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the
                                                            // framebuffer

        pgl.setThread(thread);
        checkPause();
        sketch.handleDraw();

        glfwSwapBuffers(window); // swap the color buffers

        // Poll for window events. The key callback above will only be
        // invoked during this call.
        glfwPollEvents();
      }
*/


    }
  }

//  @SuppressWarnings("serial")
//  class DummyFrame extends Frame {
//
//    public DummyFrame() {
//      super();
//    }
//
//    @Override
//    public void setResizable(boolean resizable) {
//      Display.setResizable(resizable);
//    }
//
//    @Override
//    public void setVisible(boolean visible) {
//      System.err.println("Cannot set visibility of window in OpenGL");
//    }
//
//    @Override
//    public void setTitle(String title) {
//      Display.setTitle(title);
//    }
//  }



  ///////////////////////////////////////////////////////////

  // LWJGL event handling

/*
  protected class KeyPoller extends Thread {
    protected PApplet parent;
    protected boolean stopRequested;
    protected boolean[] pressedKeys;
    protected char[] charCheys;

    KeyPoller(PApplet parent) {
      this.parent = parent;
      stopRequested = false;
      try {
        Keyboard.create();
      } catch (LWJGLException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void run() {
      pressedKeys = new boolean[256];
      charCheys = new char[256];
      Keyboard.enableRepeatEvents(true);
      while (true) {
        if (stopRequested) break;

        Keyboard.poll();
        while (Keyboard.next()) {
          if (stopRequested) break;

          long millis = Keyboard.getEventNanoseconds() / 1000000L;
          char keyChar = Keyboard.getEventCharacter();
          int keyCode = Keyboard.getEventKey();

          if (keyCode >= pressedKeys.length) continue;

          int modifiers = 0;
          if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ||
              Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            modifiers |= Event.SHIFT;
          }
          if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) ||
              Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            modifiers |= Event.CTRL;
          }
          if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) ||
              Keyboard.isKeyDown(Keyboard.KEY_RMETA)) {
            modifiers |= Event.META;
          }
          if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) ||
              Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            // LWJGL maps the menu key and the alt key to the same value.
            modifiers |= Event.ALT;
          }

          int keyPCode = LWJGLtoAWTCode(keyCode);
          if ((short)(keyChar) <= 0) {
            keyChar = PConstants.CODED;
          }

          int action = 0;
          if (Keyboard.getEventKeyState()) {
            action = KeyEvent.PRESS;
            pressedKeys[keyCode] = true;
            charCheys[keyCode] = keyChar;
          } else if (pressedKeys[keyCode]) {
            action = KeyEvent.RELEASE;
            pressedKeys[keyCode] = false;
            keyChar = charCheys[keyCode];
          }

          KeyEvent ke = new KeyEvent(null, millis,
                                     action, modifiers,
                                     keyChar, keyPCode);
          parent.postEvent(ke);
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          // http://stackoverflow.com/questions/1024651/do-i-have-to-worry-about-interruptedexceptions-if-i-dont-interrupt-anything-mys/1024719#1024719
//          e.printStackTrace();
          Thread.currentThread().interrupt(); // restore interrupted status
          break;
        }
      }
    }

    public void requestStop() {
      stopRequested = true;
    }
  }
*/

  /*
  protected class MousePoller extends Thread {
    protected PApplet parent;
    protected boolean stopRequested;
    protected boolean pressed;
    protected boolean inside;
    protected long startedClickTime;
    protected int startedClickButton;

    MousePoller(PApplet parent) {
      this.parent = parent;
      stopRequested = false;
      try {
        Mouse.create();
      } catch (LWJGLException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void run() {
      while (true) {
        if (stopRequested) break;

        Mouse.poll();
        while (Mouse.next()) {
          if (stopRequested) break;

          long millis = Mouse.getEventNanoseconds() / 1000000L;

          int modifiers = 0;
          if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ||
              Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            modifiers |= Event.SHIFT;
          }
          if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) ||
              Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            modifiers |= Event.CTRL;
          }
          if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) ||
              Keyboard.isKeyDown(Keyboard.KEY_RMETA)) {
            modifiers |= Event.META;
          }
          if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) ||
              Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            // LWJGL maps the menu key and the alt key to the same value.
            modifiers |= Event.ALT;
          }

//          PApplet.println(Mouse.getX(), Mouse.getY(), offsetX, offsetY);
          int x = Mouse.getX() - (int)offsetX;
          int y = sketchHeight - (Mouse.getY() - (int)offsetY);
          int button = 0;
          if (Mouse.isButtonDown(0)) {
            button = PConstants.LEFT;
          } else if (Mouse.isButtonDown(1)) {
            button = PConstants.RIGHT;
          } else if (Mouse.isButtonDown(2)) {
            button = PConstants.CENTER;
          }

          int action = 0;
          if (button != 0) {
            if (pressed) {
              action = MouseEvent.DRAG;
            } else {
              action = MouseEvent.PRESS;
              pressed = true;
            }
          } else if (pressed) {
            action = MouseEvent.RELEASE;

            if (presentMode) {
              if (20 < Mouse.getX() && Mouse.getX() < 20 + 100 &&
                  20 < Mouse.getY() && Mouse.getY() < 20 + 50) {
                System.err.println("clicked on exit button");
//              if (externalMessages) {
//                System.err.println(PApplet.EXTERNAL_QUIT);
//                System.err.flush();  // important
//              }
                sketch.exit();
              }
            }

            pressed = false;
          } else {
            action = MouseEvent.MOVE;
          }

          if (inside) {
            if (!Mouse.isInsideWindow()) {
              inside = false;
              action = MouseEvent.EXIT;
            }
          } else {
            if (Mouse.isInsideWindow()) {
              inside = true;
              action = MouseEvent.ENTER;
            }
          }

          int count = 0;
          if (Mouse.getEventButtonState()) {
            startedClickTime = millis;
            startedClickButton = button;
          } else {
            if (action == MouseEvent.RELEASE) {
              boolean clickDetected = millis - startedClickTime < 500;
              if (clickDetected) {
                // post a RELEASE event, in addition to the CLICK event.
                MouseEvent me = new MouseEvent(null, millis, action, modifiers,
                                               x, y, button, count);
                parent.postEvent(me);
                action = MouseEvent.CLICK;
                count = 1;
              }
            }
          }

          MouseEvent me = new MouseEvent(null, millis, action, modifiers,
                                         x, y, button, count);
          parent.postEvent(me);
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
//          e.printStackTrace();
          Thread.currentThread().interrupt(); // restore interrupted status
          break;
        }
      }
    }

    public void requestStop() {
      stopRequested = true;
    }
  }

  // AWT to LWJGL key constants conversion.
  protected static final int[] LWJGL_KEY_CONVERSION;
   // Conversion LWJGL -> AWT keycode. Taken from GTGE library
   // https://code.google.com/p/gtge/
  static {
    // LWJGL -> AWT conversion
    // used for keypressed and keyreleased
    // mapping Keyboard.KEY_ -> KeyEvent.VK_
    LWJGL_KEY_CONVERSION = new int[Keyboard.KEYBOARD_SIZE];

    // loops through all of the registered keys in KeyEvent
    Field[] keys = java.awt.event.KeyEvent.class.getFields();
    for (int i = 0; i < keys.length; i++) {
      try {
        // Converts the KeyEvent constant name to the LWJGL constant
        // name
        String field = "KEY_" + keys[i].getName().substring(3);
        Field lwjglKey = Keyboard.class.getField(field);

        // print key mapping
        // System.out.println(field + " " + lwjglKey.getInt(null) + "="
        // + keys[i].getInt(null));

        // Sets LWJGL index to be the KeyCode value
        LWJGL_KEY_CONVERSION[lwjglKey.getInt(null)] = keys[i].getInt(null);

      } catch (Exception e) {
      }
    }

    try {
      LWJGL_KEY_CONVERSION[Keyboard.KEY_BACK] = java.awt.event.KeyEvent.VK_BACK_SPACE;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_LBRACKET] = java.awt.event.KeyEvent.VK_BRACELEFT;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_RBRACKET] = java.awt.event.KeyEvent.VK_BRACERIGHT;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_APOSTROPHE] = java.awt.event.KeyEvent.VK_QUOTE;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_GRAVE] = java.awt.event.KeyEvent.VK_BACK_QUOTE;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_BACKSLASH] = java.awt.event.KeyEvent.VK_BACK_SLASH;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_CAPITAL] = java.awt.event.KeyEvent.VK_CAPS_LOCK;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_NUMLOCK] = java.awt.event.KeyEvent.VK_NUM_LOCK;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_SCROLL] = java.awt.event.KeyEvent.VK_SCROLL_LOCK;

      // two to one buttons mapping
      LWJGL_KEY_CONVERSION[Keyboard.KEY_RETURN] = java.awt.event.KeyEvent.VK_ENTER;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_NUMPADENTER] = java.awt.event.KeyEvent.VK_ENTER;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_LCONTROL] = java.awt.event.KeyEvent.VK_CONTROL;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_RCONTROL] = java.awt.event.KeyEvent.VK_CONTROL;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_LSHIFT] = java.awt.event.KeyEvent.VK_SHIFT;
      LWJGL_KEY_CONVERSION[Keyboard.KEY_RSHIFT] = java.awt.event.KeyEvent.VK_SHIFT;
    }
    catch (Exception e) {
    }
  }


  protected int LWJGLtoAWTCode(int code) {
    try {
      return LWJGL_KEY_CONVERSION[code];
    }
    catch (ArrayIndexOutOfBoundsException e) {
      System.err.println("ERROR: Invalid LWJGL KeyCode " + code);
      return -1;
    }
  }
  */
}
