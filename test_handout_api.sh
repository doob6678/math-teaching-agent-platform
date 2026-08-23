#!/bin/bash

BASE_URL="http://localhost:8080"
COOKIE_FILE="cookies.txt"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== 讲义生成API测试 ===${NC}"

# 测试主题列表
declare -a TOPICS=(
    "为高一学生讲解二次函数的顶点式和配方法，包含配方法的推导过程和实际例题"
    "讲解圆锥曲线中抛物线的定义和标准方程，重点是焦点和准线的关系"
    "为高二学生讲解导数的几何意义和切线方程的求法，配合图像说明"
)

declare -a TASK_IDS=()

# 创建讲义任务
for i in "${!TOPICS[@]}"; do
    TOPIC="${TOPICS[$i]}"
    echo -e "\n${YELLOW}[任务 $((i+1))] 创建讲义: ${TOPIC}${NC}"

    RESPONSE=$(curl -s -X POST "${BASE_URL}/api/handouts" \
        -H "Content-Type: application/json" \
        -b "${COOKIE_FILE}" \
        -d "{\"subject\":\"${TOPIC}\",\"gradeLevel\":\"HIGH_SCHOOL\",\"difficulty\":\"MEDIUM\"}")

    echo "响应: ${RESPONSE}"

    # 提取taskId (简单的grep方式)
    TASK_ID=$(echo "${RESPONSE}" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)

    if [ -n "${TASK_ID}" ]; then
        TASK_IDS+=("${TASK_ID}")
        echo -e "${GREEN}✓ 任务创建成功: ${TASK_ID}${NC}"
    else
        echo -e "${RED}✗ 任务创建失败${NC}"
    fi

    sleep 2
done

echo -e "\n${GREEN}=== 所有任务已创建 ===${NC}"
echo "任务ID列表:"
for id in "${TASK_IDS[@]}"; do
    echo "  - ${id}"
done

# 保存任务ID到文件
printf "%s\n" "${TASK_IDS[@]}" > task_ids.txt
echo -e "\n任务ID已保存到 task_ids.txt"
