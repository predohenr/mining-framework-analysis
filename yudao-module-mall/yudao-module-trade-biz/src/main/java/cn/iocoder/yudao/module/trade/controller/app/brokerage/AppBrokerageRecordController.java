package cn.iocoder.yudao.module.trade.controller.app.brokerage;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.Resource;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.RequestMapping;
import cn.iocoder.yudao.module.trade.dal.dataobject.brokerage.BrokerageRecordDO;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import cn.iocoder.yudao.module.trade.controller.app.brokerage.vo.record.AppBrokerageRecordPageReqVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import cn.iocoder.yudao.module.trade.controller.app.brokerage.vo.record.AppBrokerageRecordRespVO;
import cn.iocoder.yudao.module.trade.controller.app.brokerage.vo.record.AppBrokerageProductPriceRespVO;
import cn.iocoder.yudao.module.trade.controller.admin.brokerage.vo.record.BrokerageRecordPageReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import cn.iocoder.yudao.module.trade.service.brokerage.BrokerageRecordService;
import jakarta.validation.Valid;
import cn.iocoder.yudao.framework.dict.core.DictFrameworkUtils;
import org.springframework.web.bind.annotation.RequestParam;
import javax.annotation.Resource;
import cn.iocoder.yudao.module.trade.enums.DictTypeConstants;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 分销用户")
@RestController
@RequestMapping("/trade/brokerage-record")
@Validated
@Slf4j
public class AppBrokerageRecordController {

    @Resource
    private BrokerageRecordService brokerageRecordService;

    @GetMapping("/page")
    @Operation(summary = "获得分销记录分页")
    public CommonResult<PageResult<AppBrokerageRecordRespVO>> getBrokerageRecordPage(@Valid AppBrokerageRecordPageReqVO pageReqVO) {
        PageResult<BrokerageRecordDO> pageResult = brokerageRecordService.getBrokerageRecordPage(
                BeanUtils.toBean(pageReqVO, BrokerageRecordPageReqVO.class).setUserId(getLoginUserId()));
        return success(BeanUtils.toBean(pageResult, AppBrokerageRecordRespVO.class, recordVO ->
                recordVO.setStatusName(DictFrameworkUtils.getDictDataLabel(DictTypeConstants.BROKERAGE_RECORD_STATUS, recordVO.getStatus()))));
    }

    @GetMapping("/get-product-brokerage-price")
    @Operation(summary = "获得商品的分销金额")
    public CommonResult<AppBrokerageProductPriceRespVO> getProductBrokeragePrice(@RequestParam("spuId") Long spuId) {
        return success(brokerageRecordService.calculateProductBrokeragePrice(getLoginUserId(), spuId));
    }

}
