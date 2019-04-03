package test;

import codeanticode.lwjgl.PLWJGL;
import processing.core.*;
import processing.opengl.*;

public class PSketch extends PApplet {
  PShader blur;
  
  public void settings() {
//    size(600, 400, PLWJGL.P2D);
    size(600, 400, PLWJGL.P3D);
//    fullScreen(PLWJGL.P3D);
  }
  
  public void setup() {
//    blur = loadShader("blur.glsl"); 
//    stroke(255, 0, 0);
//    rectMode(CENTER);
  }
 
  public void draw() {
    background(255);
    
    lights();
//    ellipse(mouseX, mouseY, 50, 50);
    
    translate(mouseX, mouseY);
    rotateX(frameCount * 0.01f);
    rotateY(frameCount * 0.01f);
    box(50);
    
    
//    filter(blur);  
//    rect(mouseX, mouseY, 150, 150); 
//    ellipse(mouseX, mouseY, 100, 100);
  }
  
  public static void main(final String[] args) {    
      PApplet.main("test.PSketch");   
  }    
}
