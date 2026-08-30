from flask import Flask, jsonify
import threading
import time
import logging
import os

app = Flask(__name__)

# Импортируем бота
from bot import SlotController, Config, TelegramService

controller = SlotController()
telegram = TelegramService()

@app.route('/')
def health():
    return jsonify({
        "status": "running",
        "service": "Slot Monitor Bot",
        "task_id": Config.TASK_ID,
        "slots_known": len(controller.last_slots)
    })

@app.route('/status')
def status():
    return jsonify(controller.get_status())

@app.route('/ping')
def ping():
    return "pong"

def run_bot():
    """Запускает бота в фоновом потоке"""
    logging.info("🤖 Starting bot in background thread...")
    try:
        while True:
            logging.info("🔍 Checking slots...")
            controller.check_and_notify()
            time.sleep(Config.CHECK_INTERVAL)
    except Exception as e:
        logging.error(f"Bot error: {e}")
        telegram.send_error_notification(f"Bot crashed: {e}")

# Запускаем бота в фоновом потоке
bot_thread = threading.Thread(target=run_bot, daemon=True)
bot_thread.start()

if __name__ == "__main__":
    port = int(os.getenv("PORT", 10000))
    app.run(host="0.0.0.0", port=port)