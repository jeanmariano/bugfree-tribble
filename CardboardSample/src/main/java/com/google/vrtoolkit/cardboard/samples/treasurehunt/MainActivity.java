/*
 * Copyright 2014 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vrtoolkit.cardboard.samples.treasurehunt;

import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;

/**
 * A Cardboard sample application.
 */
public class MainActivity extends CardboardActivity implements CardboardView.StereoRenderer {

  private static final String TAG = "MainActivity";

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100.0f;

  private static final float CAMERA_Z = 0.01f;
  private static final float TIME_DELTA = 0.3f;

  private static final float YAW_LIMIT = 0.12f;
  private static final float PITCH_LIMIT = 0.12f;

  private static final int COORDS_PER_VERTEX = 3;

  /** OpenGL handles to our program attributes. */


  private static final int POS_DATA_ELEMENTS_SIZE = 3;
  private static final int NORMAL_DATA_ELEMENTS_SIZE = 3;
  private static final int COLOR_DATA_ELEMENTS_SIZE = 4;

  private static final int BYTES_PER_FLOAT = 4;
  private static final int BYTES_PER_SHORT = 2;

  private static final int STRIDE = (POS_DATA_ELEMENTS_SIZE + NORMAL_DATA_ELEMENTS_SIZE + COLOR_DATA_ELEMENTS_SIZE)
          * BYTES_PER_FLOAT;

  // We keep the light always position just above the user.
  private static final float[] LIGHT_POS_IN_WORLD_SPACE = new float[] { 0.0f, 2.0f, 0.0f, 1.0f };

  private final float[] lightPosInEyeSpace = new float[4];

  private FloatBuffer floorVertices;
  private FloatBuffer floorColors;
  private FloatBuffer floorNormals;

  private FloatBuffer cubeVertices;
  private FloatBuffer cubeColors;
  private FloatBuffer cubeFoundColors;
  private FloatBuffer cubeNormals;

  private int cubeProgram;
  private int floorProgram;
  private int heightMapProgram;

  private int cubePositionParam;
  private int cubeNormalParam;
  private int cubeColorParam;
  private int cubeModelParam;
  private int cubeModelViewParam;
  private int cubeModelViewProjectionParam;
  private int cubeLightPosParam;

  private int positionAttribute;
  private int normalAttribute;
  private int colorAttribute;

  private int floorPositionParam;
  private int floorNormalParam;
  private int floorColorParam;
  private int floorModelParam;
  private int floorModelViewParam;
  private int floorModelViewProjectionParam;
  private int floorLightPosParam;

  private float[] modelCube;
  private float[] camera;
  private float[] view;
  private float[] headView;
  private float[] modelViewProjection;
  private float[] modelView;
  private float[] modelFloor;

  private HeightMap heightMap;

  private int score = 0;
  private float objectDistance = 12f;
  private float floorDepth = 20f;

  private Vibrator vibrator;
  private CardboardOverlayView overlayView;

  /**
   * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
   *
   * @param type The type of shader we will be creating.
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The shader object handler.
   */
  private int loadGLShader(int type, int resId) {
    String code = readRawTextFile(resId);
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, code);
    GLES20.glCompileShader(shader);

    // Get the compilation status.
    final int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

    // If the compilation failed, delete the shader.
    if (compileStatus[0] == 0) {
      Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      shader = 0;
    }

    if (shader == 0) {
      throw new RuntimeException("Error creating shader.");
    }

    return shader;
  }

  /**
   * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
   *
   * @param label Label to report in case of error.
   */
  private static void checkGLError(String label) {
    int error;
    while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, label + ": glError " + error);
      throw new RuntimeException(label + ": glError " + error);
    }
  }

  /**
   * Sets the view to our CardboardView and initializes the transformation matrices we will use
   * to render our scene.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.common_ui);
    CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
    cardboardView.setRenderer(this);
    setCardboardView(cardboardView);

    modelCube = new float[16];
    camera = new float[16];
    view = new float[16];
    modelViewProjection = new float[16];
    modelView = new float[16];
    modelFloor = new float[16];
    headView = new float[16];
    vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

    overlayView = (CardboardOverlayView) findViewById(R.id.overlay);
    overlayView.show3DToast("Pull the magnet when you find an object.");
  }

  @Override
  public void onRendererShutdown() {
    Log.i(TAG, "onRendererShutdown");
  }

  @Override
  public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
  }

  /**
   * Creates the buffers we use to store information about the 3D world.
   *
   * <p>OpenGL doesn't use Java arrays, but rather needs data in a format it can understand.
   * Hence we use ByteBuffers.
   *
   * @param config The EGL configuration used when creating the surface.
   */
  @Override
  public void onSurfaceCreated(EGLConfig config) {
    heightMap = new HeightMap();

    Log.i(TAG, "onSurfaceCreated");
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well.

    ByteBuffer bbVertices = ByteBuffer.allocateDirect(WorldLayoutData.CUBE_COORDS.length * 4);
    bbVertices.order(ByteOrder.nativeOrder());
    cubeVertices = bbVertices.asFloatBuffer();
    cubeVertices.put(WorldLayoutData.CUBE_COORDS);
    cubeVertices.position(0);

    ByteBuffer bbColors = ByteBuffer.allocateDirect(WorldLayoutData.CUBE_COLORS.length * 4);
    bbColors.order(ByteOrder.nativeOrder());
    cubeColors = bbColors.asFloatBuffer();
    cubeColors.put(WorldLayoutData.CUBE_COLORS);
    cubeColors.position(0);

    ByteBuffer bbFoundColors = ByteBuffer.allocateDirect(
        WorldLayoutData.CUBE_FOUND_COLORS.length * 4);
    bbFoundColors.order(ByteOrder.nativeOrder());
    cubeFoundColors = bbFoundColors.asFloatBuffer();
    cubeFoundColors.put(WorldLayoutData.CUBE_FOUND_COLORS);
    cubeFoundColors.position(0);

    ByteBuffer bbNormals = ByteBuffer.allocateDirect(WorldLayoutData.CUBE_NORMALS.length * 4);
    bbNormals.order(ByteOrder.nativeOrder());
    cubeNormals = bbNormals.asFloatBuffer();
    cubeNormals.put(WorldLayoutData.CUBE_NORMALS);
    cubeNormals.position(0);

    // make a floor
    ByteBuffer bbFloorVertices = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COORDS.length * 4);
    bbFloorVertices.order(ByteOrder.nativeOrder());
    floorVertices = bbFloorVertices.asFloatBuffer();
    floorVertices.put(WorldLayoutData.FLOOR_COORDS);
    floorVertices.position(0);

    ByteBuffer bbFloorNormals = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_NORMALS.length * 4);
    bbFloorNormals.order(ByteOrder.nativeOrder());
    floorNormals = bbFloorNormals.asFloatBuffer();
    floorNormals.put(WorldLayoutData.FLOOR_NORMALS);
    floorNormals.position(0);

    ByteBuffer bbFloorColors = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COLORS.length * 4);
    bbFloorColors.order(ByteOrder.nativeOrder());
    floorColors = bbFloorColors.asFloatBuffer();
    floorColors.put(WorldLayoutData.FLOOR_COLORS);
    floorColors.position(0);

    int vertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.light_vertex);
    int gridShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.grid_fragment);
    int passthroughShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.passthrough_fragment);

    cubeProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(cubeProgram, vertexShader);
    GLES20.glAttachShader(cubeProgram, passthroughShader);
    GLES20.glLinkProgram(cubeProgram);
    GLES20.glUseProgram(cubeProgram);

    checkGLError("Cube program");

    cubePositionParam = GLES20.glGetAttribLocation(cubeProgram, "a_Position");
    cubeNormalParam = GLES20.glGetAttribLocation(cubeProgram, "a_Normal");
    cubeColorParam = GLES20.glGetAttribLocation(cubeProgram, "a_Color");

    cubeModelParam = GLES20.glGetUniformLocation(cubeProgram, "u_Model");
    cubeModelViewParam = GLES20.glGetUniformLocation(cubeProgram, "u_MVMatrix");
    cubeModelViewProjectionParam = GLES20.glGetUniformLocation(cubeProgram, "u_MVP");
    cubeLightPosParam = GLES20.glGetUniformLocation(cubeProgram, "u_LightPos");



    GLES20.glEnableVertexAttribArray(cubePositionParam);
    GLES20.glEnableVertexAttribArray(cubeNormalParam);
    GLES20.glEnableVertexAttribArray(cubeColorParam);

    checkGLError("Cube program params");

    heightMapProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(heightMapProgram, vertexShader);
    GLES20.glAttachShader(heightMapProgram, passthroughShader);
    GLES20.glLinkProgram(heightMapProgram);
    GLES20.glUseProgram(heightMapProgram);

    checkGLError("Cube program");

    positionAttribute = GLES20.glGetAttribLocation(heightMapProgram, "a_Position");
    normalAttribute = GLES20.glGetAttribLocation(heightMapProgram, "a_Normal");
    colorAttribute = GLES20.glGetAttribLocation(heightMapProgram, "a_Color");

//    cubeModelParam = GLES20.glGetUniformLocation(heightMapProgram, "u_Model");
//    cubeModelViewParam = GLES20.glGetUniformLocation(heightMapProgram, "u_MVMatrix");
//    cubeModelViewProjectionParam = GLES20.glGetUniformLocation(heightMapProgram, "u_MVP");
//    cubeLightPosParam = GLES20.glGetUniformLocation(heightMapProgram, "u_LightPos");


    floorProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(floorProgram, vertexShader);
    GLES20.glAttachShader(floorProgram, gridShader);
    GLES20.glLinkProgram(floorProgram);
    GLES20.glUseProgram(floorProgram);

    checkGLError("Floor program");

    floorModelParam = GLES20.glGetUniformLocation(floorProgram, "u_Model");
    floorModelViewParam = GLES20.glGetUniformLocation(floorProgram, "u_MVMatrix");
    floorModelViewProjectionParam = GLES20.glGetUniformLocation(floorProgram, "u_MVP");
    floorLightPosParam = GLES20.glGetUniformLocation(floorProgram, "u_LightPos");

    floorPositionParam = GLES20.glGetAttribLocation(floorProgram, "a_Position");
    floorNormalParam = GLES20.glGetAttribLocation(floorProgram, "a_Normal");
    floorColorParam = GLES20.glGetAttribLocation(floorProgram, "a_Color");

    GLES20.glEnableVertexAttribArray(floorPositionParam);
    GLES20.glEnableVertexAttribArray(floorNormalParam);
    GLES20.glEnableVertexAttribArray(floorColorParam);

    checkGLError("Floor program params");

    GLES20.glEnable(GLES20.GL_DEPTH_TEST);

    // Object first appears directly in front of user.
    Matrix.setIdentityM(modelCube, 0);
    Matrix.translateM(modelCube, 0, 0, 0, -objectDistance);

    Matrix.setIdentityM(modelFloor, 0);
    Matrix.translateM(modelFloor, 0, 0, -floorDepth, 0); // Floor appears below user.

    checkGLError("onSurfaceCreated");
  }

  /**
   * Converts a raw text file into a string.
   *
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The context of the text file, or null in case of error.
   */
  private String readRawTextFile(int resId) {
    InputStream inputStream = getResources().openRawResource(resId);
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      reader.close();
      return sb.toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Prepares OpenGL ES before we draw a frame.
   *
   * @param headTransform The head transformation in the new frame.
   *                      (update model here)
   */
  @Override
  public void onNewFrame(HeadTransform headTransform) {
    // Build the Model part of the ModelView matrix.
    Matrix.rotateM(modelCube, 0, TIME_DELTA, 0.5f, 0.5f, 1.0f);

    // Build the camera matrix and apply it to the ModelView.
    Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

    headTransform.getHeadView(headView, 0);

    checkGLError("onReadyToDraw");
  }

  /**
   * Draws a frame for an eye.
   *
   * @param eye The eye to render. Includes all required transformations.
   */
  @Override
  public void onDrawEye(Eye eye) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    checkGLError("colorParam");

    // Apply the eye transformation to the camera.
    Matrix.multiplyMM(view, 0, eye.getEyeView(), 0, camera, 0);

    // Set the position of the light
    Matrix.multiplyMV(lightPosInEyeSpace, 0, view, 0, LIGHT_POS_IN_WORLD_SPACE, 0);

    // Build the ModelView and ModelViewProjection matrices
    // for calculating cube position and light.
    float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);
    Matrix.multiplyMM(modelView, 0, view, 0, modelCube, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
    drawCube();

    // Set modelView for the floor, so we draw floor in the correct location
    Matrix.multiplyMM(modelView, 0, view, 0, modelFloor, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0,
      modelView, 0);
    drawFloor();
    heightMap.render();
  }

  @Override
  public void onFinishFrame(Viewport viewport) {
  }

  /**
   * Draw the cube.
   *
   * <p>We've set all of our transformation matrices. Now we simply pass them into the shader.
   */
  public void drawCube() {
    GLES20.glUseProgram(cubeProgram);

    GLES20.glUniform3fv(cubeLightPosParam, 1, lightPosInEyeSpace, 0);

    // Set the Model in the shader, used to calculate lighting
    GLES20.glUniformMatrix4fv(cubeModelParam, 1, false, modelCube, 0);

    // Set the ModelView in the shader, used to calculate lighting
    GLES20.glUniformMatrix4fv(cubeModelViewParam, 1, false, modelView, 0);

    // Set the position of the cube
    GLES20.glVertexAttribPointer(cubePositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
        false, 0, cubeVertices);

    // Set the ModelViewProjection matrix in the shader.
    GLES20.glUniformMatrix4fv(cubeModelViewProjectionParam, 1, false, modelViewProjection, 0);

    // Set the normal positions of the cube, again for shading
    GLES20.glVertexAttribPointer(cubeNormalParam, 3, GLES20.GL_FLOAT, false, 0, cubeNormals);
    GLES20.glVertexAttribPointer(cubeColorParam, 4, GLES20.GL_FLOAT, false, 0,
        isLookingAtObject() ? cubeFoundColors : cubeColors);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    checkGLError("Drawing cube");
  }

  /**
   * Draw the floor.
   *
   * <p>This feeds in data for the floor into the shader. Note that this doesn't feed in data about
   * position of the light, so if we rewrite our code to draw the floor first, the lighting might
   * look strange.
   */
  public void drawFloor() {
    GLES20.glUseProgram(floorProgram);

    // Set ModelView, MVP, position, normals, and color.
    GLES20.glUniform3fv(floorLightPosParam, 1, lightPosInEyeSpace, 0);
    GLES20.glUniformMatrix4fv(floorModelParam, 1, false, modelFloor, 0);
    GLES20.glUniformMatrix4fv(floorModelViewParam, 1, false, modelView, 0);
    GLES20.glUniformMatrix4fv(floorModelViewProjectionParam, 1, false,
        modelViewProjection, 0);
    GLES20.glVertexAttribPointer(floorPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
        false, 0, floorVertices);
    GLES20.glVertexAttribPointer(floorNormalParam, 3, GLES20.GL_FLOAT, false, 0,
        floorNormals);
    GLES20.glVertexAttribPointer(floorColorParam, 4, GLES20.GL_FLOAT, false, 0, floorColors);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

    checkGLError("drawing floor");
  }

  /**
   * Called when the Cardboard trigger is pulled.
   */
  @Override
  public void onCardboardTrigger() {
    Log.i(TAG, "onCardboardTrigger");

    if (isLookingAtObject()) {
      score++;
      overlayView.show3DToast("Found it! Look around for another one.\nScore = " + score);
      hideObject();
    } else {
      overlayView.show3DToast("Look around to find the object!");
    }

    // Always give user feedback.
    vibrator.vibrate(50);
  }

  /**
   * Find a new random position for the object.
   *
   * <p>We'll rotate it around the Y-axis so it's out of sight, and then up or down by a little bit.
   */
  private void hideObject() {
    float[] rotationMatrix = new float[16];
    float[] posVec = new float[4];

    // First rotate in XZ plane, between 90 and 270 deg away, and scale so that we vary
    // the object's distance from the user.
    float angleXZ = (float) Math.random() * 180 + 90;
    Matrix.setRotateM(rotationMatrix, 0, angleXZ, 0f, 1f, 0f);
    float oldObjectDistance = objectDistance;
    objectDistance = (float) Math.random() * 15 + 5;
    float objectScalingFactor = objectDistance / oldObjectDistance;
    Matrix.scaleM(rotationMatrix, 0, objectScalingFactor, objectScalingFactor,
        objectScalingFactor);
    Matrix.multiplyMV(posVec, 0, rotationMatrix, 0, modelCube, 12);

    // Now get the up or down angle, between -20 and 20 degrees.
    float angleY = (float) Math.random() * 80 - 40; // Angle in Y plane, between -40 and 40.
    angleY = (float) Math.toRadians(angleY);
    float newY = (float) Math.tan(angleY) * objectDistance;

    Matrix.setIdentityM(modelCube, 0);
    Matrix.translateM(modelCube, 0, posVec[0], newY, posVec[2]);
  }

  /**
   * Check if user is looking at object by calculating where the object is in eye-space.
   *
   * @return true if the user is looking at the object.
   */
  private boolean isLookingAtObject() {
    float[] initVec = { 0, 0, 0, 1.0f };
    float[] objPositionVec = new float[4];

    // Convert object space to camera space. Use the headView from onNewFrame.
    Matrix.multiplyMM(modelView, 0, headView, 0, modelCube, 0);
    Matrix.multiplyMV(objPositionVec, 0, modelView, 0, initVec, 0);

    float pitch = (float) Math.atan2(objPositionVec[1], -objPositionVec[2]);
    float yaw = (float) Math.atan2(objPositionVec[0], -objPositionVec[2]);

    return Math.abs(pitch) < PITCH_LIMIT && Math.abs(yaw) < YAW_LIMIT;
  }

  class HeightMap
  {


    static final int SIZE_PER_SIDE = 32;
    static final float MIN_POS = -5f;
    static final float POS_RANGE = 10f;

    final int[] vertexBuffer = new int[1];
    final int[] indexBuffer = new int[1];

    int indexCount;

    HeightMap() {
      try {
        final int floatsPerVertex = POS_DATA_ELEMENTS_SIZE +
                NORMAL_DATA_ELEMENTS_SIZE + COLOR_DATA_ELEMENTS_SIZE;
        final int xLength = SIZE_PER_SIDE;
        final int yLength = SIZE_PER_SIDE;

        final float[] heightMapVertexData = new float[
                xLength * yLength * floatsPerVertex];
        int offset = 0;

        //build the vertex buffer data
        for(int y=0; y<yLength; y++) {
          for(int x=0; x<xLength; x++){
            final float xRatio = x/(float)(xLength -1);
            //build counter clockwise
            final float yRatio = 1f - (y/(float)(yLength -1));
            final float xPos = MIN_POS + (xRatio*POS_RANGE);
            final float yPos = MIN_POS + (yRatio*POS_RANGE);

            //Position
            //TODO (right now a parabola)
            heightMapVertexData[offset++] = xPos;
            heightMapVertexData[offset++] = yPos;
            heightMapVertexData[offset++] = ((xPos*xPos)+(yPos*yPos))/10f;

            //calculate the normals
            //TODO (right now it is just a parabola's)
            final float xSlope = (2 * xPos) / 10f;
            final float ySlope = (2 * yPos) / 10f;
            final float[] planeVectorX = {1f, 0f, xSlope};
            final float[] planeVectorY = {0f, 1f, ySlope};
            final float[] normalVector = {
                    (planeVectorX[1] * planeVectorY[2]) - (planeVectorX[2] * planeVectorY[1]),
                    (planeVectorX[2] * planeVectorY[0]) - (planeVectorX[0] * planeVectorY[2]),
                    (planeVectorX[0] * planeVectorY[1]) - (planeVectorX[1] * planeVectorY[0])};

            // Normalize the normal
            final float length = Matrix.length(normalVector[0], normalVector[1], normalVector[2]);

            heightMapVertexData[offset++] = normalVector[0] / length;
            heightMapVertexData[offset++] = normalVector[1] / length;
            heightMapVertexData[offset++] = normalVector[2] / length;

            //Add Colours
            // TODO
            heightMapVertexData[offset++] = xRatio;
            heightMapVertexData[offset++] = yRatio;
            heightMapVertexData[offset++] = 0.5f;
            heightMapVertexData[offset++] = 1f;
          }
        }
        //build the index data
        final int numStrips = yLength -1;
        final int numDegenerate = 2 * (numStrips -1);
        final int verticesPerStrip = 2*xLength;

        final short[] heightMapIndexData = new short[(
                verticesPerStrip*numStrips) + numDegenerate];
        offset = 0;

        for(int y=0; y<numStrips; y++){
          if(y>0){
            //start chunk... degenerate, so repeat the first vertex
            heightMapIndexData[offset++] = (short)(y*yLength);
          }
          for(int x=0; x<xLength; x++){
            //chunk of the strip
            heightMapIndexData[offset++] = (short)((y*yLength)+x);
            heightMapIndexData[offset++] = (short)(((y+1) * yLength)+x);
          }
          if(y<yLength-2){
            //end chunk...degenerate so repeat last vertex
            heightMapIndexData[offset++] = (short) (((y + 1) * yLength) + (xLength - 1));
          }
        }
        indexCount = heightMapIndexData.length;
        final FloatBuffer heightMapVertexDataBuffer = ByteBuffer
                .allocateDirect(heightMapVertexData.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        heightMapVertexDataBuffer.put(heightMapVertexData).position(0);

        final ShortBuffer heightMapIndexDataBuffer = ByteBuffer.allocateDirect(
                heightMapIndexData.length*BYTES_PER_SHORT).order(ByteOrder.nativeOrder().nativeOrder())
                .asShortBuffer();
        heightMapIndexDataBuffer.put(heightMapIndexData).position(0);

        GLES20.glGenBuffers(1, vertexBuffer, 0);
        GLES20.glGenBuffers(1, indexBuffer, 0);

        if(vertexBuffer[0]>0 && indexBuffer[0] > 0){
          GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer[0]);
          GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, heightMapVertexDataBuffer.capacity() * BYTES_PER_FLOAT,
                  heightMapVertexDataBuffer, GLES20.GL_STATIC_DRAW);

          GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0]);
          GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, heightMapIndexDataBuffer.capacity()
                  * BYTES_PER_SHORT, heightMapIndexDataBuffer, GLES20.GL_STATIC_DRAW);

          GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
          GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        } else {
          System.out.println("Error in buffer creation");
        }

      } catch(Throwable t) {
        System.out.println("Error in buffer creation");
      }
    }
    void render() {
      if (vertexBuffer[0] > 0 && indexBuffer[0] > 0) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer[0]);

        // Bind Attributes
        GLES20.glVertexAttribPointer(positionAttribute, POS_DATA_ELEMENTS_SIZE, GLES20.GL_FLOAT, false,
                STRIDE, 0);
        GLES20.glEnableVertexAttribArray(positionAttribute);

        GLES20.glVertexAttribPointer(normalAttribute, NORMAL_DATA_ELEMENTS_SIZE, GLES20.GL_FLOAT, false,
                STRIDE, POS_DATA_ELEMENTS_SIZE * BYTES_PER_FLOAT);
        GLES20.glEnableVertexAttribArray(normalAttribute);

        GLES20.glVertexAttribPointer(colorAttribute, COLOR_DATA_ELEMENTS_SIZE, GLES20.GL_FLOAT, false,
                STRIDE, (POS_DATA_ELEMENTS_SIZE + NORMAL_DATA_ELEMENTS_SIZE) * BYTES_PER_FLOAT);
        GLES20.glEnableVertexAttribArray(colorAttribute);

        // Draw
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0]);
        GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, indexCount, GLES20.GL_UNSIGNED_SHORT, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
      }
    }

    void release() {
      if (vertexBuffer[0] > 0) {
        GLES20.glDeleteBuffers(vertexBuffer.length, vertexBuffer, 0);
        vertexBuffer[0] = 0;
      }
      if (indexBuffer[0] > 0) {
        GLES20.glDeleteBuffers(indexBuffer.length, indexBuffer, 0);
        indexBuffer[0] = 0;
      }
    }

  }


}
