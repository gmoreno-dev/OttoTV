/* Otto TV: the installer's step list (design: Installer). A QML sidebar,
 * like upstream's: the installer's Calamares is a debug build, and its
 * widget sidebar aborts on an icon it can't load. */
import io.calamares.ui 1.0
import io.calamares.core 1.0
import QtQuick 2.3
import QtQuick.Layouts 1.3

Rectangle {
    id: sideBar
    color: Branding.styleString(Branding.SidebarBackground)

    ColumnLayout {
        anchors.fill: parent
        anchors.topMargin: 22
        anchors.leftMargin: 12
        anchors.rightMargin: 12
        spacing: 6

        Image {
            Layout.leftMargin: 6
            Layout.bottomMargin: 22
            Layout.preferredWidth: 140
            Layout.preferredHeight: 42
            source: "file:/" + Branding.imagePath(Branding.ProductLogo)
            fillMode: Image.PreserveAspectFit
            horizontalAlignment: Image.AlignLeft
            smooth: true
        }

        Repeater {
            model: ViewManager
            Rectangle {
                property bool current: index == ViewManager.currentStepIndex
                property bool done: index < ViewManager.currentStepIndex
                Layout.fillWidth: true
                height: 40
                radius: 12
                color: current ? "#262D38" : "transparent"

                // Step marker: a ring, amber with a dark center when current,
                // filled with a check when done.
                Rectangle {
                    id: marker
                    anchors.left: parent.left
                    anchors.leftMargin: 10
                    anchors.verticalCenter: parent.verticalCenter
                    width: 18
                    height: 18
                    radius: 9
                    color: current ? "#F2B25C" : (done ? "#A9B3C1" : "transparent")
                    border.width: (current || done) ? 0 : 2
                    border.color: "#7F8A99"
                    Rectangle {
                        visible: current
                        anchors.centerIn: parent
                        width: 8
                        height: 8
                        radius: 4
                        color: "#1D232C"
                    }
                    Text {
                        visible: done
                        anchors.centerIn: parent
                        text: "✓"
                        color: "#1D232C"
                        font.pixelSize: 12
                        font.bold: true
                    }
                }
                Text {
                    anchors.left: marker.right
                    anchors.leftMargin: 10
                    anchors.right: parent.right
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    text: display
                    elide: Text.ElideRight
                    color: current ? "#F4F6F9" : (done ? "#DCE2E9" : "#A9B3C1")
                    font.pixelSize: 15
                    font.weight: current ? Font.Bold : Font.Normal
                }
            }
        }

        Item {
            Layout.fillHeight: true
        }
    }
}
