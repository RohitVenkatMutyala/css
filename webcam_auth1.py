import cv2
import tensorflow as tf
import numpy as np
from datetime import datetime
import time
import os 
# ============================================================
# CONFIGURATION
# ============================================================

MODEL_PATH = "face_classifier.keras"

CLASS_B_THRESHOLD = 0.85
CLASS_A_THRESHOLD = 0.50

CAMERA_ID = 0

# Prevent the same event from being logged repeatedly
EVENT_COOLDOWN = 5.0


# ============================================================
# LOAD TRAINED CLASSIFIER
# ============================================================

print("Loading face classifier...")

model = tf.keras.models.load_model(
    MODEL_PATH
)

print("Face classifier loaded successfully.")


# ============================================================
# FACE DETECTOR
# ============================================================

face_detector = cv2.CascadeClassifier(
    cv2.data.haarcascades +
    "haarcascade_frontalface_default.xml"
)

if face_detector.empty():

    print("Could not load face detector.")
    exit()


# ============================================================
# EVENT STATE
# ============================================================

last_class = None
last_event_time = 0

# Rolling average prediction smoothing
prediction_history = []
HISTORY_SIZE = 5


# ============================================================
# CLASS A HANDLER
# ============================================================

def handle_class_a(confidence):

    timestamp = datetime.now().isoformat(
        timespec="seconds"
    )

    print(
        f"[{timestamp}] "
        f"AUTHENTICATION SUCCESSFUL "
        f"(confidence={confidence:.3f})"
    )

    # Log authentication event
    with open(
        "authentication.log",
        "a"
    ) as log:

        log.write(
            f"{timestamp},"
            f"CLASS_A,"
            f"AUTHENTICATION_SUCCESSFUL,"
            f"{confidence:.4f}\n"
        )


# ============================================================
# CLASS B HANDLER
# ============================================================

def handle_class_b(confidence):

    timestamp = datetime.now().isoformat(timespec="seconds")

    print(
        f"[{timestamp}] "
        f"CLASS B DETECTED "
        f"(confidence={confidence:.3f})"
    )

    # Log Class-B detection
    with open("security_events.log", "a") as log:

        log.write(
            f"{timestamp}," f"CLASS_B," f"DETECTED," f"{confidence:.4f}\n"
        )

    # Simulation response (only logging, no program execution)
    print("Class-B security event logged successfully. (Silent payload simulation complete).")


# ============================================================
# UNKNOWN HANDLER
# ============================================================

def handle_unknown(confidence):

    timestamp = datetime.now().isoformat(
        timespec="seconds"
    )

    print(
        f"[{timestamp}] "
        f"UNKNOWN / UNCERTAIN FACE "
        f"(confidence={confidence:.3f})"
    )


# ============================================================
# START WEBCAM
# ============================================================

camera = cv2.VideoCapture(
    CAMERA_ID
)

if not camera.isOpened():

    print(
        "Could not open webcam."
    )

    exit()


print()
print("======================================")
print("      FACE AUTHENTICATION SYSTEM")
print("======================================")
print("Camera started.")
print("Press Q to quit.")
print()


# ============================================================
# MAIN CAMERA LOOP
# ============================================================

while True:

    # --------------------------------------------------------
    # Capture frame
    # --------------------------------------------------------

    ret, frame = camera.read()

    if not ret:

        print(
            "Could not read frame."
        )

        break


    # --------------------------------------------------------
    # Convert BGR → grayscale
    # --------------------------------------------------------

    gray = cv2.cvtColor(
        frame,
        cv2.COLOR_BGR2GRAY
    )


    # --------------------------------------------------------
    # Detect faces
    # --------------------------------------------------------

    faces = face_detector.detectMultiScale(
        gray,
        scaleFactor=1.1,
        minNeighbors=5,
        minSize=(80, 80)
    )

    # Clear prediction history if no faces are detected
    if len(faces) == 0:
        prediction_history.clear()


    # --------------------------------------------------------
    # Process every detected face
    # --------------------------------------------------------

    for (x, y, w, h) in faces:

        # ----------------------------------------------------
        # Crop face (padded to match training dataset framing)
        # ----------------------------------------------------

        # Add 60% padding around the face box
        pad_w = int(w * 0.6)
        pad_h = int(h * 0.6)

        y1 = max(0, y - pad_h)
        y2 = min(frame.shape[0], y + h + pad_h)
        x1 = max(0, x - pad_w)
        x2 = min(frame.shape[1], x + w + pad_w)

        face = frame[y1:y2, x1:x2]

        if face.size == 0:
            continue


        # ----------------------------------------------------
        # BGR → RGB
        # ----------------------------------------------------

        face_rgb = cv2.cvtColor(
            face,
            cv2.COLOR_BGR2RGB
        )


        # ----------------------------------------------------
        # Resize to model input
        # ----------------------------------------------------

        face_rgb = cv2.resize(
            face_rgb,
            (224, 224)
        )


        # ----------------------------------------------------
        # Convert to float32
        # ----------------------------------------------------

        face_array = np.asarray(
            face_rgb,
            dtype=np.float32
        )


        # ----------------------------------------------------
        # Add batch dimension
        #
        # Shape:
        # (224,224,3)
        #
        # becomes:
        # (1,224,224,3)
        # ----------------------------------------------------

        face_array = np.expand_dims(
            face_array,
            axis=0
        )


        # ----------------------------------------------------
        # MODEL PREDICTION
        #
        # class mapping:
        #
        # 0 → Class A
        # 1 → Class B
        #
        # sigmoid output = P(Class B)
        # ----------------------------------------------------

        probability = model.predict(
            face_array,
            verbose=0
        )[0][0]


        # Make sure probability is a normal float
        probability = float(
            probability
        )

        # Append to history and calculate rolling average
        prediction_history.append(probability)
        if len(prediction_history) > HISTORY_SIZE:
            prediction_history.pop(0)
            
        smoothed_probability = sum(prediction_history) / len(prediction_history)

        # ----------------------------------------------------
        # DECISION LOGIC
        # ----------------------------------------------------

        if smoothed_probability >= CLASS_B_THRESHOLD:

            # ================================================
            # CLASS B PATH
            # ================================================

            label = "CLASS B"

            confidence = smoothed_probability

            current_class = "CLASS_B"


        elif smoothed_probability <= CLASS_A_THRESHOLD:

            # ================================================
            # CLASS A PATH
            # ================================================

            label = "CLASS A"

            confidence = 1.0 - smoothed_probability

            current_class = "CLASS_A"


        else:

            # ================================================
            # UNKNOWN / UNCERTAIN PATH
            # ================================================

            label = "UNKNOWN"

            confidence = max(
                smoothed_probability,
                1.0 - smoothed_probability
            )

            current_class = "UNKNOWN"


        # ----------------------------------------------------
        # EVENT HANDLING
        # ----------------------------------------------------

        current_time = time.time()

        if (
            current_class != last_class
            or
            current_time - last_event_time
            >= EVENT_COOLDOWN
        ):

            if current_class == "CLASS_A":

                handle_class_a(
                    confidence
                )

            elif current_class == "CLASS_B":

                handle_class_b(
                    confidence
                )

            elif current_class == "UNKNOWN":

                handle_unknown(
                    confidence
                )

            last_class = current_class
            last_event_time = current_time


        # ----------------------------------------------------
        # DRAW FACE RECTANGLE
        # ----------------------------------------------------

        # Set box color based on classification
        if current_class == "CLASS_A":
            color = (0, 255, 0) # Green
            # Draw green banner at the top of the frame
            cv2.rectangle(frame, (0, 0), (frame.shape[1], 50), (0, 255, 0), -1)
            cv2.putText(frame, "ACCESS GRANTED: AUTHENTICATION SUCCESSFUL", (10, 35),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 2)
        elif current_class == "CLASS_B":
            color = (0, 0, 255) # Red
        else:
            color = (0, 255, 255) # Yellow

        cv2.rectangle(
            frame,
            (x, y),
            (x + w, y + h),
            color,
            2
        )


        # ----------------------------------------------------
        # DISPLAY CLASS + CONFIDENCE
        # ----------------------------------------------------

        text1 = f"{label} {confidence * 100:.1f}%"
        text2 = f"P(Class B) = {smoothed_probability:.3f}"

        cv2.putText(
            frame,
            text1,
            (x, y - 10),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            color,
            2
        )

        cv2.putText(
            frame,
            text2,
            (x, y + h + 20),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            color,
            2
        )

         


    # --------------------------------------------------------
    # Display camera
    # --------------------------------------------------------

    cv2.imshow(
        "Face Authentication",
        frame
    )


    # --------------------------------------------------------
    # Press Q to quit
    # --------------------------------------------------------

    if cv2.waitKey(1) & 0xFF == ord("q"):
        break


# ============================================================
# CLEANUP
# ============================================================

camera.release()

cv2.destroyAllWindows()

print()
print("Face authentication system stopped.")