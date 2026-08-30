import os
import requests
import logging
import time
import sys
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Optional, Set
from dataclasses import dataclass
import json

# ==================== КОНФИГУРАЦИЯ ====================

class Config:
    # ===== ТОКЕНЫ ИЗ ENVIRONMENT VARIABLES =====
    
    # Telegram (обязательно)
    TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
    TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", "")
    
    # 21-school (обязательно)
    ORGANIZATION_ID = os.getenv("ORGANIZATION_ID", "b4b36a53-d253-4840-9000-49061d74bf50")
    TOKEN_ID = os.getenv("TOKEN_ID", "")
    JSESSIONID = os.getenv("JSESSIONID", "")
    
    # Настройки проекта
    TASK_ID = os.getenv("TASK_ID", "1269319")
    CHECK_INTERVAL = int(os.getenv("CHECK_INTERVAL", "30"))
    DAYS_BACK = int(os.getenv("DAYS_BACK", "7"))
    DAYS_FORWARD = int(os.getenv("DAYS_FORWARD", "30"))
    
    @staticmethod
    def get_time_range():
        now = datetime.now(timezone.utc)
        from_date = (now - timedelta(days=Config.DAYS_BACK)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        to_date = (now + timedelta(days=Config.DAYS_FORWARD)).strftime("%Y-%m-%dT%H:%M:%S.999Z")
        return from_date, to_date
    
    @staticmethod
    def validate():
        """Проверяет наличие всех необходимых переменных"""
        errors = []
        if not Config.TELEGRAM_BOT_TOKEN:
            errors.append("TELEGRAM_BOT_TOKEN not set")
        if not Config.TELEGRAM_CHAT_ID:
            errors.append("TELEGRAM_CHAT_ID not set")
        if not Config.TOKEN_ID:
            errors.append("TOKEN_ID not set")
        if not Config.JSESSIONID:
            errors.append("JSESSIONID not set")
        return errors


# ==================== МОДЕЛИ ====================

@dataclass
class TimeSlot:
    start: str
    end: str
    valid_start_times: List[str]
    staff_slot: bool
    
    def is_student_slot(self) -> bool:
        return not self.staff_slot
    
    @classmethod
    def from_dict(cls, data: dict) -> 'TimeSlot':
        return cls(
            start=data.get('start', ''),
            end=data.get('end', ''),
            valid_start_times=data.get('validStartTimes', []),
            staff_slot=data.get('staffSlot', False)
        )


@dataclass
class SlotResponse:
    check_duration: int
    time_slots: List[TimeSlot]
    review_by_student_count: int = 0
    relevant_review_by_students_count: int = 0
    review_by_inspection_staff_count: int = 0
    relevant_review_by_inspection_staff_count: int = 0
    p2p_requirement_status: str = ""
    
    @classmethod
    def from_api_response(cls, data: dict) -> 'SlotResponse':
        slots_data = data.get('data', {}).get('student', {}).get('getNameLessStudentTimeslotsForReview', {})
        
        time_slots = [
            TimeSlot.from_dict(slot)
            for slot in slots_data.get('timeSlots', [])
        ]
        
        project_info = slots_data.get('projectReviewsInfo', {})
        
        return cls(
            check_duration=slots_data.get('checkDuration', 30),
            time_slots=time_slots,
            review_by_student_count=project_info.get('reviewByStudentCount', 0),
            relevant_review_by_students_count=project_info.get('relevantReviewByStudentsCount', 0),
            review_by_inspection_staff_count=project_info.get('reviewByInspectionStaffCount', 0),
            relevant_review_by_inspection_staff_count=project_info.get('relevantReviewByInspectionStaffCount', 0),
            p2p_requirement_status=project_info.get('p2pRequirementStatus', '')
        )
    
    def get_student_slots(self) -> List[TimeSlot]:
        return [slot for slot in self.time_slots if slot.is_student_slot()]
    
    def has_available_slots(self) -> bool:
        return len(self.get_student_slots()) > 0


# ==================== СЕРВИСЫ ====================

class SchoolAPI:
    def __init__(self):
        self.base_url = "https://platform.21-school.ru/services/graphql"
        self.session = requests.Session()
    
    def get_slots(self, task_id: str = None) -> Optional[SlotResponse]:
        if task_id is None:
            task_id = Config.TASK_ID
        
        from_date, to_date = Config.get_time_range()
        
        # ТОЧНО КАК В POSTMAN
        query = """query calendarGetNameLessStudentTimeslotsForReview($from: DateTime!, $taskId: ID!, $to: DateTime!) {
  student {
    getNameLessStudentTimeslotsForReview(from: $from, taskId: $taskId, to: $to) {
      checkDuration
      projectReviewsInfo {
        ...ProjectReviewsInfo
        __typename
      }
      timeSlots {
        ...CalendarNameLessTimeslot
        __typename
      }
      __typename
    }
    __typename
  }
}

fragment ProjectReviewsInfo on ProjectReviewsInfo {
  reviewByStudentCount
  relevantReviewByStudentsCount
  reviewByInspectionStaffCount
  relevantReviewByInspectionStaffCount
  p2pRequirementStatus
  __typename
}

fragment CalendarNameLessTimeslot on CalendarNamelessTimeSlot {
  start
  end
  validStartTimes
  staffSlot
  __typename
}"""
        
        payload = {
            "operationName": "calendarGetNameLessStudentTimeslotsForReview",
            "query": query,
            "variables": {
                "taskId": task_id,
                "from": from_date,
                "to": to_date
            }
        }
        
        cookie = f"tokenId={Config.TOKEN_ID}; JSESSIONID={Config.JSESSIONID}"
        
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "schoolid": Config.ORGANIZATION_ID,
            "Cookie": cookie,
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        }
        
        try:
            response = self.session.post(
                self.base_url,
                headers=headers,
                json=payload,
                timeout=15
            )
            
            if response.status_code == 200:
                data = response.json()
                
                if 'errors' in data:
                    logging.error(f"GraphQL errors: {json.dumps(data['errors'], indent=2)}")
                    return None
                
                return SlotResponse.from_api_response(data)
            else:
                logging.error(f"HTTP {response.status_code}")
                if response.text:
                    logging.error(f"Response: {response.text[:500]}")
                if 'x-bad-request' in response.headers:
                    logging.error(f"x-bad-request: {response.headers['x-bad-request']}")
                return None
                
        except Exception as e:
            logging.error(f"Request error: {e}")
            return None


class TelegramService:
    def __init__(self):
        self.token = Config.TELEGRAM_BOT_TOKEN
        self.chat_id = Config.TELEGRAM_CHAT_ID
        self.base_url = f"https://api.telegram.org/bot{self.token}"
        self.last_error_sent = 0
    
    def send_message(self, text: str, parse_mode: str = "Markdown") -> bool:
        if not self.token or not self.chat_id:
            logging.error("Telegram config missing!")
            return False
        
        try:
            response = requests.post(
                f"{self.base_url}/sendMessage",
                json={
                    "chat_id": self.chat_id,
                    "text": text,
                    "parse_mode": parse_mode
                },
                timeout=10
            )
            if response.status_code == 200:
                logging.info("✅ Message sent to Telegram")
                return True
            else:
                logging.error(f"Failed to send: {response.text}")
                return False
        except Exception as e:
            logging.error(f"Telegram error: {e}")
            return False
    
    def send_startup_message(self):
        """Отправляет сообщение о запуске"""
        text = f"🔄 *Мониторинг запущен на Render*\n\n"
        text += f"🆔 Проект: `{Config.TASK_ID}`\n"
        text += f"⏱ Проверка каждые {Config.CHECK_INTERVAL} сек\n"
        text += f"🌐 Render Worker активен"
        return self.send_message(text)
    
    def send_slot_notification(self, task_id: str, slots: List[TimeSlot]) -> bool:
        if not slots:
            return False
        
        sorted_slots = sorted(slots, key=lambda s: s.start)
        
        text = f"🔔 *НОВЫЙ СЛОТ ДОСТУПЕН!*\n\n"
        text += f"🆔 Проект: `{task_id}`\n\n"
        text += f"🕐 *Доступные слоты:*\n"
        
        for slot in sorted_slots[:10]:
            dt = datetime.fromisoformat(slot.start.replace('Z', '+00:00'))
            local_time = dt.astimezone()
            text += f"• `{slot.start}` → {local_time.strftime('%H:%M %d.%m')}\n"
        
        if len(sorted_slots) > 10:
            text += f"\n... и еще {len(sorted_slots) - 10} слотов"
        
        text += f"\n\n📝 Запишись скорее!"
        
        return self.send_message(text)
    
    def send_error_notification(self, error_message: str) -> bool:
        current_time = time.time()
        if current_time - self.last_error_sent < 300:  # 5 минут
            return False
        
        self.last_error_sent = current_time
        return self.send_message(f"⚠️ *Ошибка мониторинга*\n\n{error_message[:500]}")
    
    def send_shutdown_message(self):
        """Отправляет сообщение об остановке"""
        return self.send_message("🛑 *Мониторинг остановлен*")


# ==================== КОНТРОЛЛЕР ====================

class SlotController:
    def __init__(self):
        self.api = SchoolAPI()
        self.telegram = TelegramService()
        self.last_slots: Set[str] = set()
        self.is_first_check = True
        self.task_id = Config.TASK_ID
        self.error_count = 0
        self.consecutive_errors = 0
        self.check_count = 0
    
    def check_and_notify(self) -> bool:
        self.check_count += 1
        
        try:
            response = self.api.get_slots(self.task_id)
            
            if response is None:
                self.consecutive_errors += 1
                self.error_count += 1
                
                logging.warning(f"❌ Failed to get slots (error #{self.error_count}, consecutive: {self.consecutive_errors})")
                
                if self.consecutive_errors >= 3:
                    self.telegram.send_error_notification(
                        f"Не удалось получить слоты для {self.task_id}\n"
                        f"Ошибка #{self.error_count}\n"
                        "Проверь токены в Environment Variables"
                    )
                    self.consecutive_errors = 0
                return False
            
            # Успешный запрос
            self.consecutive_errors = 0
            
            student_slots = response.get_student_slots()
            current_slots = {slot.start for slot in student_slots}
            
            if self.is_first_check:
                self.last_slots = current_slots
                self.is_first_check = False
                logging.info(f"📋 Initial state: {len(current_slots)} slots found")
                
                if current_slots:
                    logging.info(f"🎯 Found {len(current_slots)} available slots on start")
                    self.telegram.send_slot_notification(self.task_id, student_slots)
                return False
            
            # Находим новые слоты
            new_slots = current_slots - self.last_slots
            
            if new_slots:
                new_objects = [s for s in student_slots if s.start in new_slots]
                logging.info(f"🎉 NEW SLOTS FOUND: {len(new_slots)}")
                for slot in sorted(new_objects)[:3]:
                    logging.info(f"  🕐 {slot.start}")
                
                self.telegram.send_slot_notification(self.task_id, new_objects)
                self.last_slots = current_slots
                return True
            
            # Проверяем, не исчезли ли слоты
            removed = self.last_slots - current_slots
            if removed:
                logging.info(f"🗑️ Slots removed: {len(removed)}")
                self.last_slots = current_slots
            
            return False
            
        except Exception as e:
            logging.error(f"❌ Unexpected error: {e}")
            self.consecutive_errors += 1
            return False
    
    def get_status(self) -> Dict:
        return {
            "task_id": self.task_id,
            "check_count": self.check_count,
            "known_slots": len(self.last_slots),
            "error_count": self.error_count,
            "consecutive_errors": self.consecutive_errors,
            "is_monitoring": True,
            "last_check": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        }


# ==================== ГЛАВНАЯ ====================

def setup_logging():
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S',
        handlers=[
            logging.StreamHandler(sys.stdout)
        ]
    )


def main():
    setup_logging()
    
    print("=" * 60)
    print("🚀 SLOT MONITOR BOT - RENDER WORKER")
    print("=" * 60)
    
    # Проверяем конфигурацию
    errors = Config.validate()
    if errors:
        logging.error("❌ Configuration errors:")
        for err in errors:
            logging.error(f"   - {err}")
        logging.error("\n💡 Set environment variables in Render:")
        logging.error("   TELEGRAM_BOT_TOKEN")
        logging.error("   TELEGRAM_CHAT_ID")
        logging.error("   TOKEN_ID")
        logging.error("   JSESSIONID")
        sys.exit(1)
    
    logging.info(f"📚 Project ID: {Config.TASK_ID}")
    logging.info(f"⏱  Check interval: {Config.CHECK_INTERVAL} sec")
    logging.info(f"📅 Looking {Config.DAYS_BACK} days back, {Config.DAYS_FORWARD} days forward")
    logging.info("🤖 Render Worker started")
    
    # Отправляем стартовое сообщение
    telegram = TelegramService()
    telegram.send_startup_message()
    
    # Тестовый запрос
    logging.info("🔍 Testing API connection...")
    api = SchoolAPI()
    test_response = api.get_slots(Config.TASK_ID)
    
    if test_response is None:
        logging.error("❌ API test failed! Check TOKEN_ID and JSESSIONID")
        telegram.send_error_notification(
            "API test failed on startup!\n"
            "Check TOKEN_ID and JSESSIONID in Environment Variables"
        )
        # Продолжаем работу, но с предупреждением
    else:
        slots = test_response.get_student_slots()
        logging.info(f"✅ API test successful! Found {len(slots)} slots")
    
    # Создаем контроллер
    controller = SlotController()
    
    logging.info("\n🔄 Starting monitoring loop...\n")
    
    try:
        while True:
            logging.info(f"🔍 Check #{controller.check_count + 1}")
            controller.check_and_notify()
            
            # Логируем статус каждые 10 проверок
            if controller.check_count % 10 == 0:
                status = controller.get_status()
                logging.info(f"📊 Status: {status['known_slots']} known slots, {status['error_count']} errors")
            
            time.sleep(Config.CHECK_INTERVAL)
            
    except KeyboardInterrupt:
        logging.info("\n👋 Shutting down...")
        telegram.send_shutdown_message()
    except Exception as e:
        logging.error(f"❌ Fatal error: {e}")
        telegram.send_error_notification(f"Fatal error: {e}")
        raise


if __name__ == "__main__":
    main()