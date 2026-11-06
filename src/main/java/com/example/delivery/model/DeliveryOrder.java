package com.example.delivery.model;

import com.example.delivery.util.TextUtils;
import java.util.List;

/**
 * Represents a parsed delivery order from the {@code ĐIỆN THOẠI GIAO HÀNG} GUI.
 *
 * <p>Example from gameplay videos (v1 & v3):</p>
 * <ul>
 *   <li>Header: {@code ĐƠN #4 [Siêu khó]}</li>
 *   <li>Item line: {@code Hàng: 1 x Bánh kem Tạm ★}</li>
 *   <li>Capital line: {@code Tiền vốn lấy hàng: 1,312,500 xu}</li>
 *   <li>Salary line: {@code Lương: 6,300,000 xu + 1,900,000 xu mỗi 1000 block khoảng cách}</li>
 *   <li>Bonus line: {@code Thưởng thêm: 3 Capsule}</li>
 *   <li>Distance line: {@code Khách cách bạn: 5,000 - 10,000 block}</li>
 *   <li>Action line: {@code BẤM TRÁI: NHẬN ĐƠN} (or {@code ĐÃ NHẬN - đến CỬA HÀNG lấy hàng})</li>
 * </ul>
 */
public record DeliveryOrder(
	String orderId,
	String difficulty,
	String itemName,
	int quantity,
	long capitalCostXu,
	long baseRewardXu,
	int bonusCapsules,
	String distanceRangeInfo,
	int slotIndex,
	boolean alreadyAccepted,
	List<String> rawTooltipLines
) {
	public DeliveryOrder(
		String orderId,
		String difficulty,
		String itemName,
		int quantity,
		long capitalCostXu,
		String distanceRangeInfo,
		int slotIndex,
		boolean alreadyAccepted,
		List<String> rawTooltipLines
	) {
		this(
			orderId,
			difficulty,
			itemName,
			quantity,
			capitalCostXu,
			0L,
			0,
			distanceRangeInfo,
			slotIndex,
			alreadyAccepted,
			rawTooltipLines
		);
	}

	/**
	 * Numeric difficulty rank used to prioritize the hardest and highest-paying order:
	 * {@code Cực khó (60) > Siêu khó (50) > Rất khó (40) > Khó (30) > Thường/Trung bình (20) > Dễ (10)}.
	 */
	public int difficultyRank() {
		if (difficulty == null || difficulty.isBlank()) {
			return 15;
		}
		String lower = TextUtils.normalizeLower(difficulty);
		if (lower.contains("thần thoại") || lower.contains("huyền thoại") || lower.contains("ác mộng") || lower.contains("địa ngục")) {
			return 70;
		}
		if (lower.contains("cực khó")) {
			return 60;
		}
		if (lower.contains("siêu khó")) {
			return 50;
		}
		if (lower.contains("rất khó")) {
			return 40;
		}
		if (lower.contains("khó")) {
			return 30;
		}
		if (lower.contains("trung bình") || lower.contains("thường") || lower.contains("bình thường")) {
			return 20;
		}
		if (lower.contains("rất dễ")) {
			return 5;
		}
		if (lower.contains("dễ") || lower.contains("tập sự")) {
			return 10;
		}
		return 15;
	}

	public String displaySummary() {
		String diffSuffix = (difficulty != null && !difficulty.isBlank()) ? " [" + difficulty + "]" : "";
		return orderId + diffSuffix + " (" + quantity + "x " + itemName + ")";
	}

	public String formattedRewardSummary() {
		StringBuilder sb = new StringBuilder();
		if (baseRewardXu > 0) {
			sb.append(String.format("%,d xu", baseRewardXu));
		} else if (capitalCostXu > 0) {
			sb.append(String.format("Vốn %,d xu", capitalCostXu));
		} else {
			sb.append("Standard");
		}
		if (bonusCapsules > 0) {
			sb.append(" + ").append(bonusCapsules).append(" Capsule");
		}
		return sb.toString();
	}
}