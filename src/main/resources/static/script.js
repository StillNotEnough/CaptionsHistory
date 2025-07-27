// script.js
class SpeechTranslator {
    constructor() {
        this.recognition = null;
        this.websocket = null;
        this.isRecording = false;
        this.transcriptions = [];
        this.isRestarting = false;
        
        this.initializeElements();
        this.initializeWebSocket();
        this.initializeSpeechRecognition();
        this.bindEvents();
        this.loadHistory();
    }

    initializeElements() {
        this.startBtn = document.getElementById('startBtn');
        this.stopBtn = document.getElementById('stopBtn');
        this.clearBtn = document.getElementById('clearBtn');
        this.recordingIndicator = document.getElementById('recordingIndicator');
        this.currentText = document.getElementById('currentText');
        this.historyContainer = document.getElementById('historyContainer');
        this.connectionStatus = document.getElementById('connectionStatus');
    }

    initializeWebSocket() {
        const wsUrl = 'ws://localhost:8080/voice-translation';
        try {
            this.websocket = new WebSocket(wsUrl);
            this.websocket.onopen = () => {
                console.log('WebSocket connected');
                this.updateConnectionStatus('connected', 'Connected');
            };
            this.websocket.onmessage = (event) => {
                const data = JSON.parse(event.data);
                this.handleTranslationResponse(data);
            };
            this.websocket.onclose = () => {
                console.log('WebSocket disconnected');
                this.updateConnectionStatus('disconnected', 'Disconnected');
                setTimeout(() => {
                    this.initializeWebSocket();
                }, 3000);
            };
            this.websocket.onerror = (error) => {
                console.error('WebSocket error:', error);
                this.updateConnectionStatus('disconnected', 'Connection Error');
            };
        } catch (error) {
            console.error('Failed to create WebSocket:', error);
            this.updateConnectionStatus('disconnected', 'Connection Failed');
        }
    }

    initializeSpeechRecognition() {
        if ('SpeechRecognition' in window || 'webkitSpeechRecognition' in window) {
            const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            this.recognition = new SpeechRecognition();
            
            this.recognition.continuous = true;
            this.recognition.interimResults = true;
            this.recognition.lang = 'en-US';

            this.recognition.onresult = (event) => {
                let interimTranscript = '';
                let finalTranscript = '';

                for (let i = event.resultIndex; i < event.results.length; i++) {
                    const transcript = event.results[i][0].transcript;
                    if (event.results[i].isFinal) {
                        finalTranscript += transcript;
                    } else {
                        interimTranscript += transcript;
                    }
                }

                this.currentText.textContent = finalTranscript + interimTranscript || 'Listening...';

                if (finalTranscript) {
                    this.sendForTranslation(finalTranscript.trim());
                }
            };

            this.recognition.onerror = (event) => {
                console.error('Speech recognition error:', event.error);
                this.showToast('Speech recognition error: ' + event.error, 'error');
                this.stopRecording();
            };

            this.recognition.onend = () => {
                console.log('Speech recognition ended');
                if (this.isRecording && !this.isRestarting) {
                    this.isRestarting = true;
                    setTimeout(() => {
                        this.recognition.start();
                        this.isRestarting = false;
                    }, 100);
                } else {
                    this.currentText.textContent = 'Click "Start Recording" to begin speaking...';
                }
            };
        } else {
            this.showToast('Speech recognition not supported in this browser', 'error');
            this.startBtn.disabled = true;
        }
    }

    bindEvents() {
        this.startBtn.addEventListener('click', () => this.startRecording());
        this.stopBtn.addEventListener('click', () => this.stopRecording());
        this.clearBtn.addEventListener('click', () => this.clearHistory());
    }

    startRecording() {
        if (!this.recognition) return;
        if (this.isRecording) return;

        if (this.websocket?.readyState !== WebSocket.OPEN) {
            this.showToast('WebSocket is not connected. Please wait for connection...', 'error');
            return;
        }

        this.isRecording = true;
        this.startBtn.disabled = true;
        this.stopBtn.disabled = false;
        this.recordingIndicator.classList.add('active');
        this.currentText.textContent = 'Listening...';

        this.recognition.start();
        this.showToast('Recording started');
    }

    stopRecording() {
        if (!this.recognition) return;
        if (!this.isRecording) return;

        this.isRecording = false;
        this.isRestarting = false;
        this.startBtn.disabled = false;
        this.stopBtn.disabled = true;
        this.recordingIndicator.classList.remove('active');
        this.currentText.textContent = 'Click "Start Recording" to begin speaking...';

        this.recognition.stop();
        this.showToast('Recording stopped');
    }

    sendForTranslation(text) {
        if (this.websocket?.readyState === WebSocket.OPEN) {
            const message = {
                type: 'TRANSLATE',
                originalText: text,
                sourceLang: 'en',
                targetLang: 'es',
                timestamp: new Date().toISOString()
            };
            this.websocket.send(JSON.stringify(message));
            console.log('Sent for translation:', text);
        } else {
            console.error('WebSocket not connected');
            this.showToast('Connection lost. Please wait for reconnection...', 'error');
        }
    }

    handleTranslationResponse(data) {
        if (data.type === 'TRANSLATION_RESULT') {
            const transcription = {
                english: data.originalText,
                spanish: data.translatedText,
                timestamp: new Date(data.timestamp)
            };

            this.transcriptions.push(transcription);
            this.saveHistory();
            this.addTranscriptionToHistory(transcription);
        } else if (data.type === 'ERROR') {
            console.error('Translation error:', data.message);
            this.showToast('Translation error: ' + data.message, 'error');
        }
    }

    addTranscriptionToHistory(transcription) {
        const card = document.createElement('div');
        card.className = 'transcription-card';
        
        card.innerHTML = `
            <div class="transcription-header">
                <span class="language-label">🇺🇸 English</span>
                <span class="timestamp">${this.formatTime(transcription.timestamp)}</span>
            </div>
            <div class="transcription-text">${transcription.english}</div>
            <div class="transcription-header">
                <span class="language-label">🇪🇸 Spanish</span>
            </div>
            <div class="translation-text">${transcription.spanish}</div>
        `;

        this.historyContainer.appendChild(card);
        this.historyContainer.scrollTop = this.historyContainer.scrollHeight;
    }

    formatTime(date) {
        return date.toLocaleTimeString('en-US', {
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    clearHistory() {
        this.transcriptions = [];
        this.historyContainer.innerHTML = '';
        localStorage.removeItem('transcription_history');
        this.showToast('History cleared');
    }

    saveHistory() {
        localStorage.setItem('transcription_history', JSON.stringify(this.transcriptions));
    }

    loadHistory() {
        const saved = localStorage.getItem('transcription_history');
        if (saved) {
            this.transcriptions = JSON.parse(saved).map(t => ({
                ...t,
                timestamp: new Date(t.timestamp)
            }));
            
            this.transcriptions.forEach(t => this.addTranscriptionToHistory(t));
        }
    }

    updateConnectionStatus(status, text) {
        this.connectionStatus.textContent = text;
        this.connectionStatus.className = `connection-status ${status}`;
    }

    showToast(message, type = 'success') {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        document.body.appendChild(toast);

        setTimeout(() => toast.classList.add('show'), 100);
        
        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => document.body.removeChild(toast), 300);
        }, 3000);
    }
}

// Initialize the app when the page loads
document.addEventListener('DOMContentLoaded', () => {
    new SpeechTranslator();
});