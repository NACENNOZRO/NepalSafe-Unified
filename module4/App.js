import React, { useState, useEffect, useRef } from 'react';
import {
  StyleSheet,
  Text,
  View,
  ScrollView,
  TouchableOpacity,
  Image,
  ActivityIndicator,
  Alert,
  SafeAreaView,
  Modal,
  Vibration,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import * as FileSystem from 'expo-file-system/legacy';
import { File } from 'expo-file-system';

// Backend runs quietly in the background on your machine's LAN IP
const SERVER_URL = 'http://192.168.0.162:8000';
const WS_URL = 'ws://192.168.0.162:8000/ws/alerts';

export default function App() {
  const [serverOnline, setServerOnline] = useState(false);
  const [imageUri, setImageUri] = useState(null);
  const [imageBase64, setImageBase64] = useState(null);
  const [coords, setCoords] = useState(null);
  const [locationStatus, setLocationStatus] = useState('Fetching GPS...');
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState(null);

  // Live Community Alerts & Popup System
  const [incomingAlert, setIncomingAlert] = useState(null);
  const [showAlertModal, setShowAlertModal] = useState(false);
  const [allAlerts, setAllAlerts] = useState([]);
  const seenAlertIds = useRef(new Set());
  const wsRef = useRef(null);

  useEffect(() => {
    fetchCurrentLocation();
    checkServerHealth();

    // Start WebSocket connection for instant push alerts
    initWebSocket();

    // Fallback polling for alerts every 4 seconds in case WebSocket disconnects
    const alertInterval = setInterval(() => {
      fetchLatestAlerts();
      checkServerHealth();
    }, 4000);

    return () => {
      clearInterval(alertInterval);
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, []);

  const checkServerHealth = async () => {
    try {
      const res = await fetch(`${SERVER_URL}/health`, { method: 'GET' });
      if (res.ok) {
        setServerOnline(true);
        return;
      }
      setServerOnline(false);
    } catch {
      setServerOnline(false);
    }
  };

  const triggerDisasterPopup = (alertData) => {
    if (!alertData || !alertData.id) return;
    if (seenAlertIds.current.has(alertData.id)) return;

    seenAlertIds.current.add(alertData.id);
    setIncomingAlert(alertData);
    setShowAlertModal(true);

    // Vibrate device with alert pattern
    try {
      Vibration.vibrate([0, 500, 200, 500, 200, 700]);
    } catch {
      // vibration might not be supported on all platforms/emulators
    }
  };

  const initWebSocket = () => {
    try {
      const ws = new WebSocket(WS_URL);
      wsRef.current = ws;

      ws.onopen = () => {
        setServerOnline(true);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === 'DISASTER_ALERT' && data.alert) {
            triggerDisasterPopup(data.alert);
            setAllAlerts((prev) => [data.alert, ...prev.filter((a) => a.id !== data.alert.id)]);
          } else if (data.type === 'INIT_ALERTS' && Array.isArray(data.alerts)) {
            setAllAlerts(data.alerts.reverse());
          }
        } catch (err) {
          // ignore parse errors
        }
      };

      ws.onerror = () => {
        // WebSocket error, fallback to polling
      };

      ws.onclose = () => {
        // Reconnect after 5 seconds if connection drops
        setTimeout(initWebSocket, 5000);
      };
    } catch {
      // fallback to polling
    }
  };

  const fetchLatestAlerts = async () => {
    try {
      const res = await fetch(`${SERVER_URL}/alerts/latest`);
      if (res.ok) {
        const data = await res.json();
        if (data.latest && !seenAlertIds.current.has(data.latest.id)) {
          triggerDisasterPopup(data.latest);
          setAllAlerts((prev) => [data.latest, ...prev.filter((a) => a.id !== data.latest.id)]);
        }
      }
    } catch {
      // offline
    }
  };

  const fetchCurrentLocation = async () => {
    setLocationStatus('Requesting GPS permission...');
    try {
      const { status } = await Location.requestForegroundPermissionsAsync();
      if (status !== 'granted') {
        setLocationStatus('GPS permission denied (will use EXIF/fallback)');
        return;
      }
      setLocationStatus('Acquiring live coordinates...');
      const loc = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      setCoords({
        latitude: loc.coords.latitude,
        longitude: loc.coords.longitude,
      });
      setLocationStatus(
        `📍 ${loc.coords.latitude.toFixed(4)}, ${loc.coords.longitude.toFixed(4)}`
      );
    } catch (err) {
      setLocationStatus('Could not get GPS (fallback to EXIF)');
    }
  };

  const takePhoto = async () => {
    const { status } = await ImagePicker.requestCameraPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert('Permission Denied', 'Camera permission is required to capture photos.');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ImagePicker.MediaTypeOptions?.Images || ['images'],
      allowsEditing: false,
      quality: 0.8,
      base64: true,
      exif: true,
    });
    if (!result.canceled && result.assets && result.assets.length > 0) {
      setImageUri(result.assets[0].uri);
      setImageBase64(result.assets[0].base64 || null);
      setReport(null);
    }
  };

  const pickImage = async () => {
    const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert('Permission Denied', 'Gallery permission is required to pick photos.');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions?.Images || ['images'],
      allowsEditing: false,
      quality: 0.8,
      base64: true,
      exif: true,
    });
    if (!result.canceled && result.assets && result.assets.length > 0) {
      setImageUri(result.assets[0].uri);
      setImageBase64(result.assets[0].base64 || null);
      setReport(null);
    }
  };

  // Upload image reliably:
  // 1. Try native FileSystem.uploadAsync from expo-file-system/legacy
  // 2. Fallback to /analyze-json with Base64 payload
  const analyzeImage = async () => {
    if (!imageUri) {
      Alert.alert('No Image', 'Please capture or pick an image first.');
      return;
    }

    setLoading(true);
    setReport(null);

    let analysisSuccess = false;
    let resultData = null;

    // Method 1: Native FileSystem.uploadAsync (legacy API)
    try {
      const uploadParams = {
        user_id: 'citizen_mobile_expo',
      };
      if (coords) {
        uploadParams.lat = String(coords.latitude);
        uploadParams.lng = String(coords.longitude);
      }

      if (FileSystem.uploadAsync) {
        const uploadResult = await FileSystem.uploadAsync(
          `${SERVER_URL}/analyze`,
          imageUri,
          {
            httpMethod: 'POST',
            uploadType: FileSystem.FileSystemUploadType?.MULTIPART || 0,
            fieldName: 'image',
            parameters: uploadParams,
          }
        );

        if (uploadResult.status >= 200 && uploadResult.status < 300) {
          resultData = JSON.parse(uploadResult.body);
          analysisSuccess = true;
        } else {
          console.log('uploadAsync non-200 response status:', uploadResult.status);
        }
      }
    } catch (fsErr) {
      console.log('uploadAsync failed, falling back to base64 JSON upload:', fsErr?.message || fsErr);
    }

    // Method 2: Automatic Base64 JSON fallback
    if (!analysisSuccess) {
      try {
        let base64Data = imageBase64;

        // Try getting base64 via new File class if not already in state
        if (!base64Data) {
          try {
            const fileObj = new File(imageUri);
            if (typeof fileObj.base64 === 'function') {
              base64Data = await fileObj.base64();
            }
          } catch (fileErr) {
            console.log('New File API base64 read skipped:', fileErr?.message || fileErr);
          }
        }

        // Try getting base64 via legacy readAsStringAsync
        if (!base64Data && FileSystem.readAsStringAsync) {
          try {
            base64Data = await FileSystem.readAsStringAsync(imageUri, {
              encoding: FileSystem.EncodingType?.Base64 || 'base64',
            });
          } catch (readErr) {
            console.log('FileSystem.readAsStringAsync read failed:', readErr?.message || readErr);
          }
        }

        if (!base64Data) {
          throw new Error('Could not read image data for upload. Please try selecting the image again.');
        }

        const jsonResponse = await fetch(`${SERVER_URL}/analyze-json`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: JSON.stringify({
            image_base64: base64Data,
            filename: 'disaster_photo.jpg',
            user_id: 'citizen_mobile_expo',
            lat: coords ? coords.latitude : null,
            lng: coords ? coords.longitude : null,
          }),
        });

        if (jsonResponse.ok) {
          resultData = await jsonResponse.json();
          analysisSuccess = true;
        } else {
          const errText = await jsonResponse.text();
          throw new Error(`Server error (${jsonResponse.status}): ${errText}`);
        }
      } catch (jsonErr) {
        console.error('Base64 upload error:', jsonErr);
        Alert.alert(
          'Analysis Failed',
          `Could not connect to backend:\n${jsonErr.message}\n\nPlease check that your phone is connected to the same Wi-Fi network and backend is running at ${SERVER_URL}.`
        );
      }
    }

    setLoading(false);

    if (analysisSuccess && resultData) {
      setReport(resultData);
      setServerOnline(true);

      // If disaster detected, add to local alerts feed
      if (resultData.analysis && resultData.analysis.is_problem) {
        const myAlert = {
          id: 'my-' + Date.now(),
          timestamp_utc: resultData.report_generated_utc,
          disaster_type: resultData.analysis.disaster_type,
          verdict: resultData.analysis.verdict,
          severity: resultData.analysis.severity,
          severity_score: resultData.analysis.severity_score,
          confidence_pct: resultData.analysis.confidence_pct,
          location: resultData.location,
          user_id: 'You',
        };
        seenAlertIds.current.add(myAlert.id);
        setAllAlerts((prev) => [myAlert, ...prev]);
      }
    }
  };

  const getSeverityBadgeColor = (sev) => {
    switch (sev) {
      case 'Severe':
        return '#dc2626';
      case 'High':
        return '#ea580c';
      case 'Moderate':
        return '#d97706';
      default:
        return '#16a34a';
    }
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={styles.container}>
        {/* Header with Background Server Status */}
        <View style={styles.header}>
          <View style={styles.headerTitleRow}>
            <Text style={styles.appTitle}>🚨 Disaster Analysis</Text>
            <View
              style={[
                styles.statusPill,
                { backgroundColor: serverOnline ? '#14532d' : '#7f1d1d' },
              ]}
            >
              <View
                style={[
                  styles.statusDot,
                  { backgroundColor: serverOnline ? '#22c55e' : '#ef4444' },
                ]}
              />
              <Text style={styles.statusPillText}>
                {serverOnline ? 'Backend Online' : 'Connecting...'}
              </Text>
            </View>
          </View>
          <Text style={styles.subtitle}>Citizen Live Damage & Emergency Alert System</Text>
        </View>

        {/* GPS Location Bar */}
        <View style={styles.card}>
          <View style={styles.locationRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.cardLabel}>Live GPS Location</Text>
              <Text style={styles.locationText}>{locationStatus}</Text>
            </View>
            <TouchableOpacity
              style={styles.refreshLocBtn}
              onPress={fetchCurrentLocation}
            >
              <Text style={styles.refreshLocText}>🔄 Refresh</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Image Picker / Camera */}
        <View style={styles.card}>
          <Text style={styles.cardLabel}>Capture / Select Disaster Photo</Text>
          <View style={styles.buttonRow}>
            <TouchableOpacity style={styles.actionBtn} onPress={takePhoto}>
              <Text style={styles.actionBtnText}>📷 Take Photo</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.actionBtn, styles.galleryBtn]}
              onPress={pickImage}
            >
              <Text style={styles.actionBtnText}>🖼️ Choose Gallery</Text>
            </TouchableOpacity>
          </View>

          {imageUri ? (
            <View style={styles.previewContainer}>
              <Image source={{ uri: imageUri }} style={styles.previewImage} />
              <TouchableOpacity
                style={styles.removeBtn}
                onPress={() => {
                  setImageUri(null);
                  setImageBase64(null);
                  setReport(null);
                }}
              >
                <Text style={styles.removeBtnText}>✕ Remove Photo</Text>
              </TouchableOpacity>
            </View>
          ) : (
            <View style={styles.placeholderBox}>
              <Text style={styles.placeholderText}>
                No photo selected.{'\n'}Capture disaster area or select from gallery.
              </Text>
            </View>
          )}

          {/* Submit Button */}
          {imageUri && (
            <TouchableOpacity
              style={[styles.analyzeBtn, loading && styles.analyzeBtnDisabled]}
              onPress={analyzeImage}
              disabled={loading}
            >
              {loading ? (
                <View style={styles.loadingRow}>
                  <ActivityIndicator color="#ffffff" size="small" />
                  <Text style={styles.analyzeBtnText}> Analyzing Disaster Signals...</Text>
                </View>
              ) : (
                <Text style={styles.analyzeBtnText}>⚡ Run Disaster Analysis & Broadcast</Text>
              )}
            </TouchableOpacity>
          )}
        </View>

        {/* My Analysis Result Section */}
        {report && (
          <View style={styles.card}>
            <Text style={styles.cardLabel}>Analysis Result</Text>

            {/* Verdict Box */}
            <View
              style={[
                styles.verdictBox,
                {
                  backgroundColor: report.analysis.is_problem ? '#7f1d1d' : '#14532d',
                  borderColor: report.analysis.is_problem ? '#ef4444' : '#22c55e',
                },
              ]}
            >
              <Text style={styles.verdictText}>{report.analysis.verdict}</Text>
            </View>

            {/* Severity & Confidence */}
            <View style={styles.metricGrid}>
              <View style={styles.metricBox}>
                <Text style={styles.metricTitle}>Severity</Text>
                <View
                  style={[
                    styles.severityBadge,
                    {
                      backgroundColor: getSeverityBadgeColor(report.analysis.severity),
                    },
                  ]}
                >
                  <Text style={styles.severityBadgeText}>
                    {report.analysis.severity}
                  </Text>
                </View>
                <Text style={styles.metricSub}>
                  Score: {report.analysis.severity_score}
                </Text>
              </View>

              <View style={styles.metricBox}>
                <Text style={styles.metricTitle}>Confidence</Text>
                <Text style={styles.metricValue}>
                  {report.analysis.confidence_pct}%
                </Text>
                <Text style={styles.metricSub}>AI Certainty</Text>
              </View>
            </View>

            {/* Signal Details */}
            <View style={styles.detailsBox}>
              <Text style={styles.detailsHeader}>Heuristic Breakdown:</Text>
              <Text style={styles.detailItem}>
                🌊 Flood Water:{' '}
                <Text style={styles.bold}>
                  {report.analysis.flood_detected ? 'DETECTED' : 'None'}
                </Text>{' '}
                ({report.analysis.water_coverage_pct}% coverage)
              </Text>
              <Text style={styles.detailItem}>
                🏚️ Structural Damage:{' '}
                <Text style={styles.bold}>
                  {report.analysis.damage_detected ? 'DETECTED' : 'None'}
                </Text>{' '}
                (score {report.analysis.texture_score})
              </Text>
              <Text style={styles.detailItem}>
                🔥 Fire / Thermal:{' '}
                <Text style={styles.bold}>
                  {report.analysis.fire_detected ? 'DETECTED' : 'None'}
                </Text>{' '}
                ({report.analysis.fire_coverage_pct}% fire-color)
              </Text>
              <Text style={styles.detailItem}>
                📍 Coordinates:{' '}
                <Text style={styles.bold}>
                  {report.location?.lat !== null
                    ? `${report.location.lat}, ${report.location.lng} (${report.location.source})`
                    : 'Unavailable'}
                </Text>
              </Text>
            </View>
          </View>
        )}

        {/* Real-time Community Alerts Feed */}
        {allAlerts.length > 0 && (
          <View style={styles.card}>
            <Text style={styles.cardLabel}>🚨 Live Community Disaster Feed</Text>
            <Text style={styles.feedSubtitle}>
              Broadcasted across all citizen devices in real-time:
            </Text>
            {allAlerts.map((alt, idx) => (
              <View key={alt.id || idx} style={styles.feedCard}>
                <View style={styles.feedHeaderRow}>
                  <Text style={styles.feedType}>⚠️ {alt.disaster_type}</Text>
                  <View
                    style={[
                      styles.smallBadge,
                      { backgroundColor: getSeverityBadgeColor(alt.severity) },
                    ]}
                  >
                    <Text style={styles.smallBadgeText}>{alt.severity}</Text>
                  </View>
                </View>
                {alt.location?.lat && (
                  <Text style={styles.feedLocation}>
                    📍 Lat: {alt.location.lat}, Lng: {alt.location.lng}
                  </Text>
                )}
                <Text style={styles.feedTime}>
                  🕒 {new Date(alt.timestamp_utc).toLocaleTimeString()}
                </Text>
              </View>
            ))}
          </View>
        )}
      </ScrollView>

      {/* EMERGENCY POPUP MODAL (Appears on ALL users' phones when disaster is reported) */}
      <Modal
        visible={showAlertModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowAlertModal(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.alertModalBox}>
            <View style={styles.modalHeader}>
              <Text style={styles.sirenIcon}>🚨</Text>
              <Text style={styles.modalAlertTitle}>EMERGENCY ALERT</Text>
              <Text style={styles.sirenIcon}>🚨</Text>
            </View>

            <View style={styles.warningBanner}>
              <Text style={styles.warningBannerText}>
                CRITICAL DISASTER DETECTED
              </Text>
            </View>

            {incomingAlert && (
              <View style={styles.modalContent}>
                <Text style={styles.modalDisasterType}>
                  {incomingAlert.disaster_type}
                </Text>

                <View style={styles.modalRow}>
                  <Text style={styles.modalLabel}>Severity Level:</Text>
                  <View
                    style={[
                      styles.smallBadge,
                      {
                        backgroundColor: getSeverityBadgeColor(
                          incomingAlert.severity
                        ),
                      },
                    ]}
                  >
                    <Text style={styles.smallBadgeText}>
                      {incomingAlert.severity} (Score {incomingAlert.severity_score})
                    </Text>
                  </View>
                </View>

                {incomingAlert.location?.lat !== null && incomingAlert.location?.lat !== undefined && (
                  <View style={styles.modalRow}>
                    <Text style={styles.modalLabel}>Location:</Text>
                    <Text style={styles.modalValHighlight}>
                      📍 {incomingAlert.location.lat}, {incomingAlert.location.lng}
                    </Text>
                  </View>
                )}

                <View style={styles.advisoryBox}>
                  <Text style={styles.advisoryTitle}>⚠️ Urgent Safety Advice:</Text>
                  <Text style={styles.advisoryText}>
                    {incomingAlert.disaster_type.toLowerCase().includes('flood')
                      ? 'Stay clear of low-lying waterways and flooded roads. Move to higher ground immediately.'
                      : incomingAlert.disaster_type.toLowerCase().includes('fire')
                      ? 'Immediate fire threat. Evacuate following marked safe routes and stay upwind from smoke.'
                      : 'Severe structural hazards detected. Avoid damaged buildings and watch for falling debris.'}
                  </Text>
                </View>
              </View>
            )}

            <TouchableOpacity
              style={styles.modalDismissBtn}
              onPress={() => setShowAlertModal(false)}
            >
              <Text style={styles.modalDismissText}>I Understand / Dismiss</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  container: {
    padding: 16,
    paddingBottom: 40,
  },
  header: {
    marginBottom: 16,
    marginTop: 8,
  },
  headerTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  appTitle: {
    fontSize: 22,
    fontWeight: '800',
    color: '#f8fafc',
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  statusPillText: {
    color: '#f8fafc',
    fontSize: 11,
    fontWeight: '700',
  },
  subtitle: {
    fontSize: 13,
    color: '#94a3b8',
    marginTop: 4,
  },
  card: {
    backgroundColor: '#1e293b',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  cardLabel: {
    fontSize: 13,
    fontWeight: '700',
    color: '#cbd5e1',
    textTransform: 'uppercase',
    letterSpacing: 0.8,
  },
  locationRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  locationText: {
    color: '#38bdf8',
    fontSize: 14,
    fontWeight: '600',
    marginTop: 4,
  },
  refreshLocBtn: {
    backgroundColor: '#334155',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
  },
  refreshLocText: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '600',
  },
  buttonRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 10,
    marginBottom: 12,
  },
  actionBtn: {
    flex: 1,
    backgroundColor: '#2563eb',
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  galleryBtn: {
    backgroundColor: '#0d9488',
  },
  actionBtnText: {
    color: '#ffffff',
    fontWeight: '700',
    fontSize: 14,
  },
  placeholderBox: {
    height: 120,
    backgroundColor: '#0f172a',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
    borderStyle: 'dashed',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 16,
  },
  placeholderText: {
    color: '#64748b',
    textAlign: 'center',
    fontSize: 13,
    lineHeight: 20,
  },
  previewContainer: {
    alignItems: 'center',
    marginTop: 6,
  },
  previewImage: {
    width: '100%',
    height: 220,
    borderRadius: 8,
    backgroundColor: '#0f172a',
  },
  removeBtn: {
    marginTop: 8,
    paddingVertical: 4,
  },
  removeBtnText: {
    color: '#f87171',
    fontSize: 13,
    fontWeight: '600',
  },
  analyzeBtn: {
    backgroundColor: '#dc2626',
    marginTop: 14,
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
  },
  analyzeBtnDisabled: {
    opacity: 0.6,
  },
  analyzeBtnText: {
    color: '#ffffff',
    fontSize: 15,
    fontWeight: '800',
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  verdictBox: {
    marginTop: 10,
    padding: 12,
    borderRadius: 8,
    borderWidth: 1.5,
    alignItems: 'center',
  },
  verdictText: {
    color: '#ffffff',
    fontWeight: '800',
    fontSize: 15,
    textAlign: 'center',
  },
  metricGrid: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 12,
  },
  metricBox: {
    flex: 1,
    backgroundColor: '#0f172a',
    borderRadius: 8,
    padding: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#334155',
  },
  metricTitle: {
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '700',
    marginBottom: 6,
  },
  metricValue: {
    color: '#f8fafc',
    fontSize: 20,
    fontWeight: '800',
  },
  metricSub: {
    color: '#64748b',
    fontSize: 11,
    marginTop: 4,
  },
  severityBadge: {
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 12,
    marginBottom: 4,
  },
  severityBadgeText: {
    color: '#ffffff',
    fontWeight: '800',
    fontSize: 13,
  },
  detailsBox: {
    backgroundColor: '#0f172a',
    borderRadius: 8,
    padding: 12,
    marginTop: 12,
    borderWidth: 1,
    borderColor: '#334155',
  },
  detailsHeader: {
    color: '#cbd5e1',
    fontWeight: '700',
    fontSize: 13,
    marginBottom: 8,
  },
  detailItem: {
    color: '#94a3b8',
    fontSize: 13,
    marginBottom: 6,
    lineHeight: 18,
  },
  bold: {
    color: '#f8fafc',
    fontWeight: '700',
  },
  feedSubtitle: {
    color: '#94a3b8',
    fontSize: 12,
    marginTop: 4,
    marginBottom: 10,
  },
  feedCard: {
    backgroundColor: '#0f172a',
    padding: 12,
    borderRadius: 8,
    marginBottom: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#ef4444',
  },
  feedHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  feedType: {
    color: '#f8fafc',
    fontSize: 14,
    fontWeight: '700',
    flex: 1,
  },
  feedLocation: {
    color: '#38bdf8',
    fontSize: 12,
    marginTop: 4,
  },
  feedTime: {
    color: '#64748b',
    fontSize: 11,
    marginTop: 2,
  },
  smallBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 6,
  },
  smallBadgeText: {
    color: '#ffffff',
    fontSize: 11,
    fontWeight: '700',
  },

  // Modal Alert Styles
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.85)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  alertModalBox: {
    width: '100%',
    backgroundColor: '#1e293b',
    borderRadius: 16,
    padding: 20,
    borderWidth: 2,
    borderColor: '#ef4444',
    shadowColor: '#ef4444',
    shadowOpacity: 0.6,
    shadowRadius: 16,
    elevation: 20,
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 8,
  },
  sirenIcon: {
    fontSize: 24,
  },
  modalAlertTitle: {
    color: '#ef4444',
    fontSize: 22,
    fontWeight: '900',
    letterSpacing: 1,
  },
  warningBanner: {
    backgroundColor: '#b91c1c',
    paddingVertical: 6,
    borderRadius: 6,
    alignItems: 'center',
    marginVertical: 12,
  },
  warningBannerText: {
    color: '#ffffff',
    fontWeight: '900',
    fontSize: 13,
    letterSpacing: 0.5,
  },
  modalContent: {
    marginVertical: 8,
  },
  modalDisasterType: {
    color: '#ffffff',
    fontSize: 18,
    fontWeight: '800',
    textAlign: 'center',
    marginBottom: 12,
  },
  modalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  modalLabel: {
    color: '#94a3b8',
    fontSize: 14,
    fontWeight: '600',
  },
  modalValHighlight: {
    color: '#38bdf8',
    fontSize: 14,
    fontWeight: '700',
  },
  advisoryBox: {
    backgroundColor: '#0f172a',
    padding: 12,
    borderRadius: 8,
    marginTop: 10,
    borderLeftWidth: 3,
    borderLeftColor: '#eab308',
  },
  advisoryTitle: {
    color: '#facc15',
    fontWeight: '700',
    fontSize: 13,
    marginBottom: 4,
  },
  advisoryText: {
    color: '#cbd5e1',
    fontSize: 12,
    lineHeight: 18,
  },
  modalDismissBtn: {
    backgroundColor: '#dc2626',
    marginTop: 16,
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  modalDismissText: {
    color: '#ffffff',
    fontWeight: '800',
    fontSize: 15,
  },
});
