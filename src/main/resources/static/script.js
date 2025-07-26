// script.js
class VoiceTranslator {
    constructor() {
        this.isListening = false;
        this.websocket = null;
        this.recognition = null;
        this.transcriptionHistory = [];
        this.initElements();
        this.initWebSocket();
        this.initSpeechRecognition();
    }
    initElements() {
        this.startBtn = document.getElementById('startBtn');
        this.clearBtn = document.getElementById('clearBtn');
        this.status = document.getElementById('status');
        this.statusText = document.getElementById('statusText');
        this.transcriptionFeed = document.getElementById('transcriptionFeed');
        this.connectionStatus = document.getElementById('connectionStatus');
        this.currentRecognition = document.getElementById('currentRecognition');
        this.currentText = document.getElementById('currentText');
        this.startBtn.addEventListener('click', () => this.toggleListening());
        this.clearBtn.addEventListener('click', () => this.clearHistory());
    }
    initWebSocket() {
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
                    this.initWebSocket();
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
    initSpeechRecognition() {
        if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
            alert('Speech recognition not supported in this browser. Please use Chrome or Edge.');
            return;
        }
        const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        this.recognition = new SpeechRecognition();
        this.recognition.continuous = true;
        this.recognition.interimResults = true;
        this.recognition.lang = 'en-US';
        this.recognition.onstart = () => {
            console.log('Speech recognition started');
            this.updateStatus(true, 'Listening...');
        };
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
            if (interimTranscript) {
                this.currentText.textContent = interimTranscript;
                this.currentRecognition.style.display = 'block';
            }
            if (finalTranscript.trim()) {
                this.currentRecognition.style.display = 'none';
                this.sendForTranslation(finalTranscript.trim());
            }
        };
        this.recognition.onerror = (event) => {
            console.error('Speech recognition error:', event.error);
            this.updateStatus(false, 'Recognition error: ' + event.error);
        };
        this.recognition.onend = () => {
            console.log('Speech recognition ended');
            if (this.isListening) {
                setTimeout(() => {
                    this.recognition.start();
                }, 100);
            } else {
                this.updateStatus(false, 'Stopped listening');
                this.currentRecognition.style.display = 'none';
            }
        };
    }
    toggleListening() {
        if (!this.recognition) {
            alert('Speech recognition is not available');
            return;
        }
        if (this.isListening) {
            this.stopListening();
        } else {
            this.startListening();
        }
    }
    startListening() {
        if (this.isListening) return; // Prevent double start
        if (this.websocket?.readyState !== WebSocket.OPEN) {
            alert('WebSocket is not connected. Please wait for connection...');
            return;
        }
        this.isListening = true;
        this.startBtn.textContent = 'Stop';
        this.startBtn.classList.add('recording');
        this.startBtn.disabled = true; // Disable to prevent double click
        this.recognition.start();
        setTimeout(() => {
            this.startBtn.disabled = false; // Re-enable after recognition starts
        }, 500);
    }
    stopListening() {
        if (!this.isListening) return; // Prevent double stop
        this.isListening = false;
        this.startBtn.textContent = 'Start';
        this.startBtn.classList.remove('recording');
        this.recognition.stop();
        this.currentRecognition.style.display = 'none';
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
            alert('Connection lost. Please wait for reconnection...');
        }
    }
    handleTranslationResponse(data) {
        if (data.type === 'TRANSLATION_RESULT') {
            this.addTranscriptionItem({
                originalText: data.originalText,
                translatedText: data.translatedText,
                timestamp: new Date(data.timestamp)
            });
        } else if (data.type === 'ERROR') {
            console.error('Translation error:', data.message);
            alert('Translation error: ' + data.message);
        }
    }
    addTranscriptionItem(item) {
        const emptyState = this.transcriptionFeed.querySelector('.empty-state');
        if (emptyState) {
            emptyState.remove();
        }
        const transcriptionItem = document.createElement('div');
        transcriptionItem.className = 'transcription-item';
        transcriptionItem.innerHTML = `
            <div class="timestamp">
                ${item.timestamp.toLocaleTimeString()}
            </div>
            <div class="original-text">
                <div class="label">English (Original)</div>
                <div class="text-content">${item.originalText}</div>
            </div>
            <div class="translated-text">
                <div class="label">Español (Translation)</div>
                <div class="text-content">${item.translatedText}</div>
            </div>
        `;
        this.transcriptionFeed.appendChild(transcriptionItem);
        this.transcriptionHistory.push(item);
        this.transcriptionFeed.scrollTop = this.transcriptionFeed.scrollHeight;
    }
    clearHistory() {
        this.transcriptionHistory = [];
        this.transcriptionFeed.innerHTML = `
            <div class="empty-state">
                <div style="    font-size: 4em; margin-bottom: 20px;">🎯</div>
                <h3>Ready to translate!</h3>
                <p>Press "Start Listening" and begin speaking in English.<br>
                Your speech will be transcribed and translated to Spanish in real-time.</p>
            </div>
        `;
    }
    updateStatus(isActive, text) {
        this.statusText.textContent = text;
        this.status.style.display = isActive ? 'inline-flex' : 'none';
    }
    updateConnectionStatus(status, text) {
        this.connectionStatus.textContent = text;
        this.connectionStatus.className = `connection-status ${status}`;
    }
}
document.addEventListener('DOMContentLoaded', () => {
    new VoiceTranslator();
});