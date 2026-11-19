/* Otto TV: what the installer shows while it copies the system. Four
 * slides (images from tools/brand/make-assets.js), 7 seconds each. */
import QtQuick 2.15;
import calamares.slideshow 1.0;

Presentation
{
    id: presentation

    Timer {
        interval: 7000
        running: presentation.activatedInCalamares
        repeat: true
        onTriggered: presentation.goToNextSlide()
    }

    Rectangle {
        anchors.fill: parent
        color: "#161B22"
        z: -1
    }

    Slide {
        anchors.fill: parent
        Image {
            source: "slide1.png"
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 480)
            fillMode: Image.PreserveAspectFit
            smooth: true
        }
    }
    Slide {
        anchors.fill: parent
        Image {
            source: "slide2.png"
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 480)
            fillMode: Image.PreserveAspectFit
            smooth: true
        }
    }
    Slide {
        anchors.fill: parent
        Image {
            source: "slide3.png"
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 480)
            fillMode: Image.PreserveAspectFit
            smooth: true
        }
    }
    Slide {
        anchors.fill: parent
        Image {
            source: "slide4.png"
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 480)
            fillMode: Image.PreserveAspectFit
            smooth: true
        }
    }

    function onActivate() { presentation.currentSlide = 0; }
    function onLeave() { }
}
