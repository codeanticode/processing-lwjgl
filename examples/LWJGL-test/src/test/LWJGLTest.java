package test;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.nio.IntBuffer;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import processing.core.*;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;

public class LWJGLTest extends PApplet {
  
  // The window handle
  private long window;
  
  public void run() {
    System.out.println("Hello LWJGL " + Version.getVersion() + "!");
    
    init();
    render();

    // Free the window callbacks and destroy the window
    glfwFreeCallbacks(window);
    glfwDestroyWindow(window);

    // Terminate GLFW and free the error callback
    glfwTerminate();
    glfwSetErrorCallback(null).free();
  }

  private void init() {
    // Setup an error callback. The default implementation
    // will print the error message in System.err.
    GLFWErrorCallback.createPrint(System.err).set();

    // Initialize GLFW. Most GLFW functions will not work before doing this.
    if ( !glfwInit() )
      throw new IllegalStateException("Unable to initialize GLFW");

    // Configure GLFW
    glfwDefaultWindowHints(); // optional, the current window hints are already the default
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

    // Create the window
    window = glfwCreateWindow(300, 300, "Hello LWJGL " + Version.getVersion() + "!", NULL, NULL);
    if ( window == NULL )
      throw new RuntimeException("Failed to create the GLFW window");

    // Setup a key callback. It will be called every time a key is pressed, repeated or released.
    glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
      if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
        glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
    });
    
    glfwSetCursorPosCallback(window, (window, x, y) -> {
      System.out.println("cursor moved " + x + ", " + y);      
    }); 

    // Get the thread stack and push a new frame
    try ( MemoryStack stack = stackPush() ) {
      IntBuffer pWidth = stack.mallocInt(1); // int*
      IntBuffer pHeight = stack.mallocInt(1); // int*

      // Get the window size passed to glfwCreateWindow
      glfwGetWindowSize(window, pWidth, pHeight);

      // Get the resolution of the primary monitor
      GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

      // Center the window
      glfwSetWindowPos(
        window,
        (vidmode.width() - pWidth.get(0)) / 2,
        (vidmode.height() - pHeight.get(0)) / 2
      );
    } // the stack frame is popped automatically

    // Make the OpenGL context current
    glfwMakeContextCurrent(window);
    // Enable v-sync
    glfwSwapInterval(1);

    // Make the window visible
    glfwShowWindow(window);
  }

  private void render() {
    // This line is critical for LWJGL's interoperation with GLFW's
    // OpenGL context, or any context that is managed externally.
    // LWJGL detects the context that is current in the current thread,
    // creates the GLCapabilities instance and makes the OpenGL
    // bindings available for use.
    GL.createCapabilities();

    // Set the clear color
    glClearColor(1.0f, 0.0f, 0.0f, 0.0f);

    // Run the rendering loop until the user has attempted to close
    // the window or has pressed the ESCAPE key.
    while ( !glfwWindowShouldClose(window) ) {
      glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

      glfwSwapBuffers(window); // swap the color buffers

      // Poll for window events. The key callback above will only be
      // invoked during this call.
      glfwPollEvents();
    }
  }  
  
  public static void main(final String[] args) {
    
      // All the following things break GLFW (in particular, mouse movement events are not detected unless pressing the mouse):
      
      // 1) calling getDefaultToolkit()
//      try {
//        Toolkit.getDefaultToolkit().setDynamicLayout(true);
//      } catch (HeadlessException e) { } 
      
      // 2) Creating an AWT frame
//     Frame frame2 = new Frame();

      // 3) Getting the local graphics environment
//      GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
//      GraphicsDevice device = ge.getDefaultScreenDevice();
//      GraphicsDevice[] displayDevices2 = ge.getScreenDevices();


      // 4) Dealing with issues related to thinking differently 
//      if (platform == MACOSX) {
//        try {
//          final String td = "processing.core.ThinkDifferent";
//          Class<?> thinkDifferent =
//            Thread.currentThread().getContextClassLoader().loadClass(td);
//          Method method =
//            thinkDifferent.getMethod("init", new Class[] { PApplet.class });
//          method.invoke(null, new Object[] { new Main() });
//        } catch (Exception e) {
//          e.printStackTrace();  // That's unfortunate
//        }
//      }
      
      new LWJGLTest().run();      
  }    
}
